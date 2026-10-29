package delivery;

import delivery.io.DataLoader;
import delivery.logic.RouteCalc;
import delivery.logic.RouteCalc.Visit;
import delivery.model.*;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.*;

public class TripCheck {
    public static void main(String[] args) {
        Path dir = Path.of("data");
        Map<String, Warehouse> warehouses = new HashMap<>();
        for (Warehouse x : DataLoader.loadWarehouses(dir.resolve("warehouses.json"))) {
            warehouses.put(x.name(), x);
        }
        List<Truck> trucks = DataLoader.loadTrucks(dir.resolve("trucks.json"));
        // склад Авто-1 и Авто-2 — для проверок ниже (каждая машина считает рейсы от своего склада)
        Warehouse w = warehouses.get(trucks.get(0).warehouse());
        Map<String, Store> stores = new HashMap<>();
        for (Store s : DataLoader.loadStores(dir.resolve("stores.json"))) {
            stores.put(s.name(), s);
        }
        List<Order> orders = DataLoader.loadOrders(dir.resolve("orders.json"));
        Map<String, Order> byId = new HashMap<>();
        for (Order o : orders) {
            byId.put(o.id(), o);
        }

        // 1. Обычные рейсы: каждая заявка на каждой машине
        for (Truck t : trucks) {
            System.out.println("== " + t.name() + " (" + t.warehouse() + ")");
            for (Order o : orders) {
                Optional<Trip> trip = RouteCalc.calcTrip(warehouses.get(t.warehouse()), stores.get(o.store()), t, o);
                if (trip.isPresent()) {
                    print(trip.get());
                } else {
                    System.out.println("  " + o.id() + "  рейс невозможен");
                }
            }
        }

        Truck auto1 = trucks.get(0);
        Truck auto2 = trucks.get(1);
        Order o1 = byId.get("З-1");
        Order o2 = byId.get("З-2");
        Order o5 = byId.get("З-5");
        Store s1 = stores.get(o1.store());
        Store s2 = stores.get(o2.store());
        Store s5 = stores.get(o5.store());

        // 2. Вес: заявка тяжелее машины
        Order heavy = new Order("ТЕСТ-вес", "Магазин-1", LocalTime.of(10, 0), LocalTime.of(14, 0),
                List.of(new OrderItem("Тест", 2000)));
        System.out.println("\n== Вес: 2000 кг на " + auto1.name() + " (" + auto1.capacityKg() + " кг)");
        System.out.println("  " + (RouteCalc.calcTrip(w, s1, auto1, heavy).isPresent()
                ? "рейс есть — ОШИБКА" : "рейс невозможен — верно"));

        // 3. Попутный рейс из двух магазинов в обоих порядках
        System.out.println("\n== З-1 + З-2 на " + auto1.name() + " в заданном порядке");
        System.out.print("  М1 → М2:");
        RouteCalc.calcRoute(w, auto1, List.of(new Visit(s1, o1), new Visit(s2, o2)))
                .ifPresentOrElse(TripCheck::print, () -> System.out.println("  невозможен"));
        System.out.print("  М2 → М1:");
        RouteCalc.calcRoute(w, auto1, List.of(new Visit(s2, o2), new Visit(s1, o1)))
                .ifPresentOrElse(TripCheck::print, () -> System.out.println("  невозможен"));

        // 4. Этап 2 на Авто-2: начинаем с рейса З-2 и по одной добавляем попутные заявки
        System.out.println("\n== Этап 2 на " + auto2.name() + ": добавляем заявки по одной");
        Trip trip = RouteCalc.calcTrip(w, s2, auto2, o2).orElseThrow();
        double separate = trip.cost();
        System.out.print("  только З-2:          ");
        print(trip);
        for (Order add : List.of(o1, o5)) {
            Trip single = RouteCalc.calcTrip(w, stores.get(add.store()), auto2, add).orElseThrow();
            separate += single.cost();
            Optional<Trip> bigger = RouteCalc.addToTrip(w, trip, stores.get(add.store()), add);
            if (bigger.isEmpty()) {
                System.out.println("  + " + add.id() + ": не помещается");
                continue;
            }
            System.out.printf("  + %s (+%.0f руб.): ", add.id(), bigger.get().cost() - trip.cost());
            trip = bigger.get();
            print(trip);
        }
        System.out.printf("  отдельными рейсами было бы %.0f руб., одним рейсом — %.0f руб.%n",
                separate, trip.cost());

        // 5. То же на Авто-1 (1000 кг): третья заявка уже не влезет по весу
        System.out.println("\n== То же на " + auto1.name() + " (1000 кг)");
        Trip t1 = RouteCalc.calcTrip(w, s2, auto1, o2).orElseThrow();
        Trip t12 = RouteCalc.addToTrip(w, t1, s1, o1).orElseThrow();
        System.out.print("  З-2 + З-1:  ");
        print(t12);
        System.out.println("  + З-5 (600 кг): " + (RouteCalc.addToTrip(w, t12, s5, o5).isPresent()
                ? "добавлена — ОШИБКА" : "не помещается — верно (800 + 600 > 1000)"));
    }

    private static void print(Trip r) {
        StringBuilder sb = new StringBuilder();
        for (Stop s : r.stops()) {
            sb.append(String.format("  %s %s %s–%s", s.order().id(), s.store().name(), s.arrival(), s.unloadEnd()));
        }
        System.out.printf("  рейс %s–%s |%s | %.0f кг, %.2f км, %.0f руб.%n",
                r.start(), r.end(), sb, r.loadKg(), r.distanceKm(), r.cost());
    }
}
