package delivery;

import delivery.io.DataException;
import delivery.io.DataLoader;
import delivery.model.Store;
import delivery.model.Truck;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.StaleProxyException;

import java.nio.file.Path;
import java.util.List;

/**
 * КЛИЕНТ: периферийный контейнер, который подключается к серверу по сети и создаёт агентов
 * из своего файла. Клиентов может быть сколько угодно, на любых компьютерах.
 * <pre>
 *   ClientMain &lt;IP сервера&gt; trucks [папка с данными] [IP этого компьютера]   — машины из trucks.json
 *   ClientMain &lt;IP сервера&gt; stores [папка с данными] [IP этого компьютера]   — магазины из stores.json
 *
 *   ClientMain 192.168.1.10 trucks
 *   ClientMain 192.168.1.10 stores
 * </pre>
 * Каждому клиенту нужен только СВОЙ файл: машинам — trucks.json, магазинам — stores.json.
 * Всё остальное (данные складов, заявки) агенты получают сообщениями.
 */
public class ClientMain {

    public static void main(String[] args) {
        if (args.length < 2 || !(args[1].equals("trucks") || args[1].equals("stores"))) {
            System.err.println("Запуск: ClientMain <IP сервера> trucks|stores [папка с данными] [IP этого компьютера]");
            System.err.println("Пример: ClientMain 192.168.1.10 stores");
            return;
        }
        String serverHost = args[0];
        String kind = args[1];
        Path dir = Path.of(args.length > 2 ? args[2] : "data");
        String localHost = args.length > 3 ? args[3] : null;

        // 1. Свой файл
        List<Truck> trucks = null;
        List<Store> stores = null;
        try {
            if (kind.equals("trucks")) {
                trucks = DataLoader.loadTrucks(dir.resolve("trucks.json"));
            } else {
                stores = DataLoader.loadStores(dir.resolve("stores.json"));
            }
        } catch (DataException e) {
            System.err.println("Ошибка в исходных данных: " + e.getMessage());
            return;
        }

        // 2. Периферийный контейнер, подключённый к серверу
        Runtime rt = Runtime.instance();              // jade.core.Runtime, не java.lang.Runtime
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, serverHost);
        profile.setParameter(Profile.MAIN_PORT, "1099");
        profile.setParameter(Profile.CONTAINER_NAME, kind.equals("trucks") ? "Машины" : "Магазины");
        if (localHost != null) {
            profile.setParameter(Profile.LOCAL_HOST, localHost);
        }
        AgentContainer container = rt.createAgentContainer(profile);
        if (container == null) {
            System.err.println("Не удалось подключиться к серверу " + serverHost + ":1099. Проверьте: "
                    + "сервер запущен, IP указан верно, компьютеры в одной сети, "
                    + "брандмауэр на сервере пропускает Java (порт 1099).");
            return;
        }

        // 3. Агенты из своего файла
        try {
            if (trucks != null) {
                for (Truck t : trucks) {
                    container.createNewAgent(t.name(), "delivery.agents.TruckAgent", new Object[]{t}).start();
                }
                System.out.println("Клиент «Машины» подключён к " + serverHost + ", машин: " + trucks.size());
            } else {
                for (Store s : stores) {
                    container.createNewAgent(s.name(), "delivery.agents.StoreAgent", new Object[]{s}).start();
                }
                System.out.println("Клиент «Магазины» подключён к " + serverHost + ", магазинов: " + stores.size());
            }
        } catch (StaleProxyException e) {
            System.err.println("Не удалось создать агента: " + e.getMessage()
                    + " (может быть, агент с таким именем уже запущен другим клиентом?)");
            e.printStackTrace();
        }
    }
}
