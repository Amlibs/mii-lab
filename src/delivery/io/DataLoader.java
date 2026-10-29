package delivery.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import delivery.model.Order;
import delivery.model.OrderItem;
import delivery.model.Store;
import delivery.model.Truck;
import delivery.model.Warehouse;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Чтение исходных данных из JSON-файлов (UTF-8) с проверкой.
 * <p>
 * Любая ошибка в файле приводит к {@link DataException} с понятным сообщением:
 * имя файла, какая запись и что не так.
 */
public final class DataLoader {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalTime.class, new LocalTimeAdapter())
            .setPrettyPrinting()
            .create();

    private DataLoader() {
    }

    // ------------------------------------------------------------------ загрузка

    /** Склады: массив JSON. Первый склад в списке — координатор (запускает этапы, собирает итоги). */
    public static List<Warehouse> loadWarehouses(Path file) {
        List<Warehouse> list = readList(file, new TypeToken<List<Warehouse>>() { }.getType());
        String f = name(file);
        for (int i = 0; i < list.size(); i++) {
            Warehouse w = list.get(i);
            String where = f + ", склад №" + (i + 1);
            require(w != null, where, "пустая запись");
            require(notBlank(w.name()), where, "не указано name");
            where = f + ", склад «" + w.name() + "»";
            require(w.workFrom() != null && w.workTo() != null, where, "не указаны workFrom / workTo");
            require(w.workFrom().isBefore(w.workTo()), where,
                    "workFrom (" + fmt(w.workFrom()) + ") должно быть раньше workTo (" + fmt(w.workTo()) + ")");
            require(w.loadingMinutes() >= 0, where, "loadingMinutes не может быть отрицательным");
        }
        requireUnique(list, Warehouse::name, f, "имя склада");
        return list;
    }

    /** Автомобили: массив JSON. */
    public static List<Truck> loadTrucks(Path file) {
        List<Truck> list = readList(file, new TypeToken<List<Truck>>() { }.getType());
        String f = name(file);
        for (int i = 0; i < list.size(); i++) {
            Truck t = list.get(i);
            String where = f + ", автомобиль №" + (i + 1);
            require(t != null, where, "пустая запись");
            require(notBlank(t.name()), where, "не указано name");
            where = f + ", автомобиль «" + t.name() + "»";
            require(notBlank(t.warehouse()), where, "не указан warehouse (склад, к которому приписана машина)");
            require(t.capacityKg() > 0, where, "capacityKg должно быть больше 0");
            require(t.speedKmh() > 0, where, "speedKmh должно быть больше 0");
            require(t.costPerKm() >= 0, where, "costPerKm не может быть отрицательным");
        }
        requireUnique(list, Truck::name, f, "имя автомобиля");
        return list;
    }

    /** Магазины: массив JSON. */
    public static List<Store> loadStores(Path file) {
        List<Store> list = readList(file, new TypeToken<List<Store>>() { }.getType());
        String f = name(file);
        for (int i = 0; i < list.size(); i++) {
            Store s = list.get(i);
            String where = f + ", магазин №" + (i + 1);
            require(s != null, where, "пустая запись");
            require(notBlank(s.name()), where, "не указано name");
            where = f + ", магазин «" + s.name() + "»";
            require(s.unloadingMinutes() >= 0, where, "unloadingMinutes не может быть отрицательным");
        }
        requireUnique(list, Store::name, f, "имя магазина");
        return list;
    }

    /** Пул заявок: массив JSON. */
    public static List<Order> loadOrders(Path file) {
        List<Order> list = readList(file, new TypeToken<List<Order>>() { }.getType());
        String f = name(file);
        for (int i = 0; i < list.size(); i++) {
            Order o = list.get(i);
            String where = f + ", заявка №" + (i + 1);
            require(o != null, where, "пустая запись");
            require(notBlank(o.id()), where, "не указан id");
            where = f + ", заявка «" + o.id() + "»";
            require(notBlank(o.store()), where, "не указан store");
            require(o.windowFrom() != null && o.windowTo() != null, where,
                    "не указано окно приёмки windowFrom / windowTo");
            require(o.windowFrom().isBefore(o.windowTo()), where,
                    "окно " + fmt(o.windowFrom()) + "–" + fmt(o.windowTo()) + ": начало должно быть раньше конца");
            require(o.items() != null && !o.items().isEmpty(), where, "нет товаров (items)");
            for (OrderItem it : o.items()) {
                require(it != null && notBlank(it.product()), where, "у товара не указано product");
                require(it.weightKg() > 0, where, "у товара «" + it.product() + "» weightKg должно быть больше 0");
            }
        }
        requireUnique(list, Order::id, f, "id заявки");
        return list;
    }

    // ------------------------------------------------------------ перекрёстные проверки

    /**
     * Проверка согласованности файлов между собой. Нужна там, где доступны все файлы
     * (например, в {@code DataCheck}); при распределённом запуске каждая машина
     * читает только свои файлы.
     *
     * @return предупреждения (заявки, которые заведомо нельзя выполнить); пустой список — всё в порядке
     * @throws DataException если заявка ссылается на несуществующий магазин
     *                       или машина приписана к несуществующему складу
     */
    public static List<String> crossCheck(List<Warehouse> warehouses, List<Truck> trucks, List<Store> stores,
                                          List<Order> orders) {
        Map<String, Store> byName = stores.stream()
                .collect(Collectors.toMap(Store::name, Function.identity()));
        Set<String> warehouseNames = warehouses.stream().map(Warehouse::name).collect(Collectors.toSet());
        for (Truck t : trucks) {
            if (!warehouseNames.contains(t.warehouse())) {
                throw new DataException("автомобиль «" + t.name() + "»: склада «" + t.warehouse()
                        + "» нет в списке складов");
            }
        }
        double maxCapacity = trucks.stream().mapToDouble(Truck::capacityKg).max().orElse(0);
        List<String> warnings = new ArrayList<>();
        for (Order o : orders) {
            String where = "заявка «" + o.id() + "»";
            Store s = byName.get(o.store());
            if (s == null) {
                throw new DataException(where + ": магазина «" + o.store() + "» нет в списке магазинов");
            }
            if (o.totalWeightKg() > maxCapacity) {
                warnings.add(where + ": вес " + kg(o.totalWeightKg())
                        + " больше вместимости любого автомобиля (" + kg(maxCapacity) + ")");
            }
            boolean anyWarehouseWorks = warehouses.stream().anyMatch(
                    w -> o.windowTo().isAfter(w.workFrom()) && o.windowFrom().isBefore(w.workTo()));
            if (!anyWarehouseWorks) {
                warnings.add(where + ": окно " + fmt(o.windowFrom()) + "–" + fmt(o.windowTo())
                        + " не пересекается с часами работы ни одного склада");
            }
            long windowMin = java.time.Duration.between(o.windowFrom(), o.windowTo()).toMinutes();
            if (windowMin < s.unloadingMinutes()) {
                warnings.add(where + ": окно приёмки (" + windowMin + " мин) короче времени разгрузки ("
                        + s.unloadingMinutes() + " мин)");
            }
        }
        return warnings;
    }

    /** Общий Gson (с поддержкой времени "HH:mm") — пригодится и для сообщений между агентами. */
    public static Gson gson() {
        return GSON;
    }

    // ------------------------------------------------------------------ служебное

    private static <T> T read(Path file, Type type) {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, type);
        } catch (NoSuchFileException e) {
            throw new DataException("Файл не найден: " + file.toAbsolutePath());
        } catch (IOException e) {
            throw new DataException("Не удалось прочитать " + file.toAbsolutePath() + ": " + e.getMessage());
        } catch (JsonParseException e) {
            String msg = String.valueOf(e.getMessage())
                    .replace("com.google.gson.stream.MalformedJsonException: ", "")
                    .replaceAll("(?s)\\s*See https://.*", "");
            throw new DataException(name(file) + ": ошибка в JSON — " + msg);
        }
    }

    private static <T> List<T> readList(Path file, Type type) {
        List<T> list = read(file, type);
        if (list == null || list.isEmpty()) {
            throw new DataException(name(file) + ": список пуст");
        }
        return list;
    }

    private static <T> void requireUnique(List<T> list, Function<T, String> key, String file, String what) {
        Set<String> seen = new HashSet<>();
        for (T t : list) {
            String k = key.apply(t);
            require(seen.add(k), file, "повторяется " + what + " «" + k + "»");
        }
    }

    private static void require(boolean ok, String where, String message) {
        if (!ok) throw new DataException(where + ": " + message);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String name(Path file) {
        return file.getFileName().toString();
    }

    public static String fmt(LocalTime t) {
        return t.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String kg(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + " кг";
    }

    /** Время в JSON записывается строкой "H:mm" или "HH:mm", например "9:40" или "14:00". */
    private static final class LocalTimeAdapter extends TypeAdapter<LocalTime> {
        @Override
        public void write(JsonWriter out, LocalTime value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(fmt(value));
        }

        @Override
        public LocalTime read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String s = in.nextString().trim();
            try {
                return LocalTime.parse(s, TIME);
            } catch (DateTimeParseException e) {
                throw new JsonParseException("время «" + s + "» должно быть в формате ЧЧ:ММ (" + in.getPath() + ")");
            }
        }
    }
}
