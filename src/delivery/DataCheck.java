package delivery;

import delivery.io.DataException;
import delivery.io.DataLoader;
import delivery.model.Order;
import delivery.model.OrderItem;
import delivery.model.Store;
import delivery.model.Truck;
import delivery.model.Warehouse;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Проверка исходных данных без запуска агентов: читает все четыре файла,
 * печатает их содержимое и предупреждения.
 * <p>
 * Аргумент: папка с данными (по умолчанию {@code data}).
 */
public class DataCheck {

    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0] : "data");
        try {
            List<Warehouse> warehouses = DataLoader.loadWarehouses(dir.resolve("warehouses.json"));
            List<Truck> trucks = DataLoader.loadTrucks(dir.resolve("trucks.json"));
            List<Store> stores = DataLoader.loadStores(dir.resolve("stores.json"));
            List<Order> orders = DataLoader.loadOrders(dir.resolve("orders.json"));

            System.out.println("Склады (" + warehouses.size() + "), координатор — " + warehouses.get(0).name() + ":");
            for (Warehouse w : warehouses) {
                System.out.printf(Locale.ROOT, "  %-10s (%.1f; %.1f), работа %s–%s, погрузка %d мин%n",
                        w.name(), w.x(), w.y(), DataLoader.fmt(w.workFrom()), DataLoader.fmt(w.workTo()),
                        w.loadingMinutes());
            }

            System.out.println("\nАвтомобили (" + trucks.size() + "):");
            for (Truck t : trucks) {
                System.out.printf(Locale.ROOT, "  %-10s %-10s %6.0f кг  %3.0f км/ч  %5.1f руб./км%n",
                        t.name(), t.warehouse(), t.capacityKg(), t.speedKmh(), t.costPerKm());
            }

            System.out.println("\nМагазины (" + stores.size() + "):");
            for (Store s : stores) {
                System.out.printf(Locale.ROOT, "  %-12s (%5.1f; %5.1f)  разгрузка %d мин%n",
                        s.name(), s.x(), s.y(), s.unloadingMinutes());
            }

            System.out.println("\nПул заявок (" + orders.size() + "):");
            for (Order o : orders) {
                String items = o.items().stream()
                        .map(OrderItem::product)
                        .collect(Collectors.joining(", "));
                System.out.printf(Locale.ROOT, "  %-5s %-12s окно %s–%s  %6.0f кг  [%s]%n",
                        o.id(), o.store(), DataLoader.fmt(o.windowFrom()), DataLoader.fmt(o.windowTo()),
                        o.totalWeightKg(), items);
            }

            List<String> warnings = DataLoader.crossCheck(warehouses, trucks, stores, orders);
            if (warnings.isEmpty()) {
                System.out.println("\nДанные корректны.");
            } else {
                System.out.println("\nПредупреждения:");
                warnings.forEach(s -> System.out.println("  ! " + s));
            }
        } catch (DataException e) {
            System.err.println("Ошибка в исходных данных: " + e.getMessage());
            System.exit(1);
        }
    }
}
