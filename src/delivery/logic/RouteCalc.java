package delivery.logic;

import delivery.model.*;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RouteCalc {
    /**
     * Сколько минут машина может ждать у магазина (начиная со второго в рейсе) до начала его окна.
     * Без ограничения "выгодным" оказался бы рейс, где машина с грузом полдня стоит у магазина.
     */
    public static final int MAX_WAIT_MINUTES = 60;

    /** Куда заехать в рейсе: магазин и заявка для него (порядок в списке = порядок объезда). */
    public record Visit(Store store, Order order) {
    }

    public static double deliveryDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow((x2 - x1), 2) + Math.pow((y2 - y1), 2));
    }

    /** Стоимость по ПОЛНОМУ пробегу рейса. */
    public static double deliveryCharge(double distance, Truck truck) {
        return distance * truck.costPerKm();
    }

    public static int deliveryTime(double distance, Truck truck) {
        return (int)Math.ceil(distance / truck.speedKmh() * 60);
    }

    /**
     * Общий расчёт рейса: склад → магазины в заданном порядке → склад.
     * Подходит для любого числа магазинов; обычный рейс — это список из одного магазина.
     */
    public static Optional<Trip> calcRoute(Warehouse warehouse, Truck truck, List<Visit> visits) {
        return calcRoute(warehouse, truck, visits, null);
    }

    /**
     * То же, но погрузка начинается не раньше notBefore (null — без ограничения).
     * Нужно машине, чтобы найти время для рейса ПОСЛЕ уже забронированных: машина вернулась
     * на склад — и только тогда может начать следующую погрузку.
     */
    public static Optional<Trip> calcRoute(Warehouse warehouse, Truck truck, List<Visit> visits,
                                           LocalTime notBefore) {
        if (visits.isEmpty()) {
            return Optional.empty();
        }

        // 1. Вес: в машине одновременно все заявки рейса
        double weight = 0;
        for (Visit v : visits) {
            weight += v.order().totalWeightKg();
        }
        if (weight > truck.capacityKg()) {
            return Optional.empty();
        }

        // 2. Начало погрузки — так, чтобы приехать в ПЕРВЫЙ магазин к началу его окна,
        //    но не раньше открытия склада
        Visit first = visits.get(0);
        int timeToFirst = deliveryTime(deliveryDistance(warehouse.x(), warehouse.y(),
                first.store().x(), first.store().y()), truck);
        LocalTime start;
        if (warehouse.workFrom().isBefore(first.order().windowFrom())) {
            start = first.order().windowFrom().minusMinutes(warehouse.loadingMinutes() + timeToFirst);
            if (start.isBefore(warehouse.workFrom())) {
                start = warehouse.workFrom();
            }
        } else {
            start = warehouse.workFrom();
        }
        if (notBefore != null && start.isBefore(notBefore)) {
            start = notBefore;   // машина ещё занята предыдущим рейсом
        }

        // 3. Объезд магазинов по порядку
        LocalTime time = start.plusMinutes(warehouse.loadingMinutes());   // выезд со склада
        double prevX = warehouse.x();
        double prevY = warehouse.y();
        double distance = 0;
        List<Stop> stops = new ArrayList<>();
        for (Visit v : visits) {
            Store s = v.store();
            Order o = v.order();

            // переезд от предыдущей точки (склада или магазина)
            double d = deliveryDistance(prevX, prevY, s.x(), s.y());
            distance += d;
            LocalTime arrival = time.plusMinutes(deliveryTime(d, truck));

            // приехали раньше окна — ждём его начала, но не слишком долго
            if (arrival.isBefore(o.windowFrom())) {
                long wait = Duration.between(arrival, o.windowFrom()).toMinutes();
                if (wait > MAX_WAIT_MINUTES) {
                    return Optional.empty();
                }
                arrival = o.windowFrom();
            }

            // разгрузка должна закончиться до конца окна
            LocalTime unloadEnd = arrival.plusMinutes(s.unloadingMinutes());
            if (unloadEnd.isAfter(o.windowTo())) {
                return Optional.empty();
            }

            stops.add(new Stop(s, o, arrival, unloadEnd));
            time = unloadEnd;
            prevX = s.x();
            prevY = s.y();
        }

        // 4. Возврат на склад до его закрытия
        double back = deliveryDistance(prevX, prevY, warehouse.x(), warehouse.y());
        distance += back;
        LocalTime end = time.plusMinutes(deliveryTime(back, truck));
        if (end.isAfter(warehouse.workTo())) {
            return Optional.empty();
        }

        return Optional.of(new Trip(truck, List.copyOf(stops), start, end, distance,
                deliveryCharge(distance, truck)));
    }

    /** Обычный рейс: склад → магазин → склад. */
    public static Optional<Trip> calcTrip(Warehouse warehouse, Store store, Truck truck, Order order) {
        return calcRoute(warehouse, truck, List.of(new Visit(store, order)));
    }

    /** Обычный рейс с погрузкой не раньше notBefore. */
    public static Optional<Trip> calcTrip(Warehouse warehouse, Store store, Truck truck, Order order,
                                          LocalTime notBefore) {
        return calcRoute(warehouse, truck, List.of(new Visit(store, order)), notBefore);
    }

    /**
     * Этап 2: добавить заявку попутно в существующий рейс (с любым числом магазинов).
     * Порядок уже имеющихся магазинов сохраняется, новый пробуем поставить в каждую позицию:
     * в начало, между магазинами, в конец ("вставка с минимальной стоимостью").
     * Лучший вариант — самый дешёвый, при равной стоимости — с более коротким интервалом занятости.
     * <p>
     * Пересечение нового рейса с ДРУГИМИ рейсами машины здесь не проверяется — это делает агент
     * через haveOverlaps (старый рейс при этом из расписания исключается).
     */
    public static Optional<Trip> addToTrip(Warehouse warehouse, Trip trip, Store store, Order order) {
        List<Visit> current = new ArrayList<>();
        for (Stop s : trip.stops()) {
            current.add(new Visit(s.store(), s.order()));
        }

        Trip best = null;
        for (int pos = 0; pos <= current.size(); pos++) {
            List<Visit> variant = new ArrayList<>(current);
            variant.add(pos, new Visit(store, order));
            Optional<Trip> t = calcRoute(warehouse, trip.truck(), variant);
            if (t.isPresent() && isBetter(t.get(), best)) {
                best = t.get();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Лучше ли рейс a, чем b: дешевле, а при равной стоимости — короче по времени. */
    private static boolean isBetter(Trip a, Trip b) {
        if (b == null) {
            return true;
        }
        if (Math.abs(a.cost() - b.cost()) > 0.01) {
            return a.cost() < b.cost();
        }
        return busyMinutes(a) < busyMinutes(b);
    }

    /** Длительность интервала занятости рейса, мин. */
    public static long busyMinutes(Trip trip) {
        return Duration.between(trip.start(), trip.end()).toMinutes();
    }

    public static boolean haveOverlaps(List<Trip> list, Trip newTrip) {
        for (Trip t : list) {
            if (t.overlaps(newTrip)) {
                return true;
            }
        }
        return false;
    }
}
