package delivery;

import delivery.io.DataException;
import delivery.io.DataLoader;
import delivery.model.Order;
import delivery.model.Store;
import delivery.model.Truck;
import delivery.model.Warehouse;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.StaleProxyException;

import java.nio.file.Path;
import java.util.List;

/**
 * Запуск всей системы на одном компьютере (для отладки): один главный контейнер,
 * в нём все агенты. Позже для двух компьютеров будут ServerMain и ClientMain.
 * <p>
 * Аргумент: папка с данными (по умолчанию {@code data}).
 */
public class LocalMain {

    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0] : "data");

        // 1. Исходные данные из файлов
        List<Warehouse> warehouses;
        List<Order> orders;
        List<Truck> trucks;
        List<Store> stores;
        try {
            warehouses = DataLoader.loadWarehouses(dir.resolve("warehouses.json"));
            orders = DataLoader.loadOrders(dir.resolve("orders.json"));
            trucks = DataLoader.loadTrucks(dir.resolve("trucks.json"));
            stores = DataLoader.loadStores(dir.resolve("stores.json"));
            // машина приписана к существующему складу, заявки — к существующим магазинам
            DataLoader.crossCheck(warehouses, trucks, stores, orders)
                    .forEach(wrn -> System.out.println("Предупреждение: " + wrn));
        } catch (DataException e) {
            System.err.println("Ошибка в исходных данных: " + e.getMessage());
            return;
        }

        // 2. Платформа JADE: главный контейнер с окном управления
        Runtime rt = Runtime.instance();              // jade.core.Runtime, не java.lang.Runtime
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.GUI, "true");
        AgentContainer main = rt.createMainContainer(profile);

        // 3. Агенты создаются в коде, данные передаются аргументами.
        //    Порядок важен: склады первыми — магазины при запуске ищут координатора в DF,
        //    машины сразу пишут своему складу.
        //    Первый склад в файле — координатор: ему передаётся пул заявок.
        try {
            for (int i = 0; i < warehouses.size(); i++) {
                Warehouse w = warehouses.get(i);
                Object[] agentArgs = (i == 0) ? new Object[]{w, orders} : new Object[]{w};
                main.createNewAgent(w.name(), "delivery.agents.WarehouseAgent", agentArgs).start();
            }

            for (Truck t : trucks) {
                main.createNewAgent(t.name(), "delivery.agents.TruckAgent",
                        new Object[]{t}).start();
            }

            for (Store s : stores) {
                main.createNewAgent(s.name(), "delivery.agents.StoreAgent",
                        new Object[]{s}).start();
            }
        } catch (StaleProxyException e) {
            System.err.println("Не удалось создать агента: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
