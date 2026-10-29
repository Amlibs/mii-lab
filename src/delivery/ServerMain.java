package delivery;

import delivery.io.DataException;
import delivery.io.DataLoader;
import delivery.model.Order;
import delivery.model.Warehouse;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.StaleProxyException;

import java.nio.file.Path;
import java.util.List;

/**
 * СЕРВЕР (компьютер 1): главный контейнер платформы JADE (AMS, DF, порт 1099) и агенты-склады.
 * Первый склад из warehouses.json — координатор, ему передаётся пул заявок из orders.json.
 * <p>
 * Нужны файлы: data/warehouses.json, data/orders.json. Сюда же в конце пишется report.html.
 * <p>
 * Аргументы (оба необязательные):
 * <pre>
 *   ServerMain [IP этого компьютера] [папка с данными]
 *   ServerMain 192.168.1.10
 * </pre>
 * IP стоит указать, если у компьютера несколько сетевых адаптеров (VirtualBox, VPN):
 * иначе JADE может выбрать «не тот» адрес, и клиенты с другого компьютера не подключатся.
 */
public class ServerMain {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : null;
        Path dir = Path.of(args.length > 1 ? args[1] : "data");

        // 1. Свои файлы: склады и пул заявок
        List<Warehouse> warehouses;
        List<Order> orders;
        try {
            warehouses = DataLoader.loadWarehouses(dir.resolve("warehouses.json"));
            orders = DataLoader.loadOrders(dir.resolve("orders.json"));
        } catch (DataException e) {
            System.err.println("Ошибка в исходных данных: " + e.getMessage());
            return;
        }

        // 2. Главный контейнер
        Runtime rt = Runtime.instance();              // jade.core.Runtime, не java.lang.Runtime
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.GUI, "true");
        profile.setParameter(Profile.MAIN_PORT, "1099");
        if (host != null) {
            profile.setParameter(Profile.MAIN_HOST, host);
            profile.setParameter(Profile.LOCAL_HOST, host);
        }
        AgentContainer main = rt.createMainContainer(profile);
        if (main == null) {
            System.err.println("Не удалось запустить главный контейнер. Возможно, порт 1099 занят "
                    + "(уже запущен другой сервер или LocalMain).");
            return;
        }

        // 3. Агенты-склады
        try {
            for (int i = 0; i < warehouses.size(); i++) {
                Warehouse w = warehouses.get(i);
                Object[] agentArgs = (i == 0) ? new Object[]{w, orders} : new Object[]{w};
                main.createNewAgent(w.name(), "delivery.agents.WarehouseAgent", agentArgs).start();
            }
        } catch (StaleProxyException e) {
            System.err.println("Не удалось создать агента: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        String addr = host != null ? host : "<IP этого компьютера>";
        System.out.println("""

                ================================================================
                 Сервер запущен. Теперь запустите клиентов:
                   машины:   ClientMain %s trucks
                   магазины: ClientMain %s stores
                 Этап 1 начнётся, когда на связи будут все магазины и машины.
                ================================================================
                """.formatted(addr, addr));
    }
}
