package delivery.agents;

import delivery.model.Order;
import delivery.model.Store;
import delivery.model.Trip;
import delivery.model.Warehouse;
import delivery.io.ReportWriter;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Склад. Складов может быть несколько, у каждого свой пул машин (машины приписаны к складу
 * в trucks.json, выезжают с него и возвращаются на него). Любой склад может выполнить любую заявку.
 * <p>
 * Один склад (первый в warehouses.json) — КООРДИНАТОР. Он НЕ центральный диспетчер и ничего не решает
 * за машины и магазины: только запускает этапы, рассылает пул заявок, собирает итоги, проверяет план
 * и формирует отчёт. Остальные склады ведут свой пул машин и сообщают машинам свои данные.
 */
public class WarehouseAgent extends Agent {
    /** Сколько раундов этапа 1 максимум (повторные раунды — для нераспределённых заявок). */
    private static final int MAX_ROUNDS = 3;

    private Warehouse warehouse;
    /** Пул заявок — есть только у координатора; у остальных складов null. */
    private List<Order> orders;
    private boolean coordinator;
    /** Свой пул машин: машины, приписанные к этому складу. */
    private final List<String> pool = new ArrayList<>();
    private final Map<String, Store> stores = new HashMap<>();
    private final Map<String, AID> storeAids = new HashMap<>();

    /** Найденные в DF машины. */
    private DFAgentDescription[] trucks = new DFAgentDescription[0];

    /** Как часто проверяем, все ли на связи, мс. */
    private static final long READY_CHECK_MS = 2000;
    /** Сколько проверок подряд число машин не должно меняться, чтобы считать, что все машины запущены. */
    private static final int STABLE_CHECKS = 2;
    /** Через сколько проверок начинаем с теми, кто есть (60 × 2 с = 2 мин). */
    private static final int MAX_READY_CHECKS = 60;
    private int lastTruckCount = -1;
    private int stableChecks = 0;
    private String lastStatus = "";
    /** Номер текущего раунда этапа 1. */
    private int round = 0;
    /** Назначенные заявки: номер заявки → рейс. */
    private final Map<String, Trip> assigned = new LinkedHashMap<>();
    /** Заявки, по которым ждём итог в текущем раунде. */
    private final Set<String> waiting = new HashSet<>();
    /** Сколько заявок было назначено к началу раунда — чтобы понять, был ли прогресс. */
    private int assignedAtRoundStart = 0;

    // ---- этап 2 ----
    /** Сколько раундов улучшения максимум (страховка; обычно остановка раньше — когда нет передач). */
    private static final int MAX_IMPROVE_ROUNDS = 5;
    private int improveRound = 0;
    private int improveReports = 0;
    private int transfersInRound = 0;
    private int transfersTotal = 0;
    /** Затраты по плану этапа 1 — для сравнения «до/после». */
    private double stage1Cost = 0;
    /** Итоговые расписания машин: имя машины → рейсы. */
    private final Map<String, List<Trip>> finalSchedules = new LinkedHashMap<>();
    /** План после этапа 1 — для сравнения в отчёте. */
    private final Map<String, List<Trip>> stage1Plan = new LinkedHashMap<>();
    /** Куда пишем итоговый отчёт (в рабочую папку программы). */
    private static final Path REPORT_FILE = Path.of("report.html");

    @SuppressWarnings("unchecked")
    @Override
    protected void setup() {
        warehouse = (Warehouse) getArguments()[0];
        orders = getArguments().length > 1 ? (List<Order>) getArguments()[1] : null;
        coordinator = orders != null;
        if (coordinator) {
            registerInDF("warehouse", "coordinator");
        } else {
            registerInDF("warehouse");
        }

        // Любой склад: «привет» от своих машин — вносим в пул и отвечаем своими данными
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(MessageTemplate.MatchConversationId("truck-hello"));
                if (msg == null) {block(); return;}
                pool.add(msg.getSender().getLocalName());
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.INFORM);
                try {
                    reply.setContentObject(warehouse);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                send(reply);
                System.out.println("[" + getLocalName() + "] пул машин: " + pool);
            }
        });

        if (!coordinator) {
            System.out.println("[" + getLocalName() + "] запущен (" + warehouse.x() + "; " + warehouse.y() + ")");
            return;   // дальше — только обязанности координатора
        }

        // «Привет» от магазинов: запоминаем их данные и адреса
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(MessageTemplate.MatchConversationId("hello"));
                if (msg == null) {block(); return;}
                Store s = null;
                try {
                    s = (Store) msg.getContentObject();
                    stores.put(s.name(), s);
                    storeAids.put(s.name(), msg.getSender());
                } catch (UnreadableException e) {
                    e.printStackTrace();
                    return;
                }
                System.out.println("[" + getLocalName() + "] " + s.name() + " на связи (" + s.x() + "; " + s.y() + ")");
            }
        });

        // Итоги по заявкам от магазинов
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(MessageTemplate.MatchConversationId("result"));
                if (msg == null) {block(); return;}
                handleResult(msg);
            }
        });

        // Этап 2: машины сообщают итог раунда улучшения (сколько заявок передали)
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(MessageTemplate.MatchConversationId("improve-done"));
                if (msg == null) {block(); return;}
                handleImproveDone(msg);
            }
        });

        // Итоговые расписания машин
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(MessageTemplate.and(
                        MessageTemplate.MatchConversationId("schedule"),
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM)));
                if (msg == null) {block(); return;}
                handleSchedule(msg);
            }
        });

        // Ждём, пока все соберутся (на двух компьютерах агенты запускаются в разное время),
        // и только потом начинаем этап 1
        addBehaviour(new TickerBehaviour(this, READY_CHECK_MS) {
            @Override
            protected void onTick() {
                if (isEveryoneReady(getTickCount())) {
                    stop();
                    System.out.println("[" + myAgent.getLocalName() + "] все на связи: магазинов "
                            + stores.size() + ", машин " + trucks.length);
                    System.out.println("\n========== ЭТАП 1: первоначальный план ==========");
                    startRound(orders);
                }
            }
        });
        System.out.println("[" + getLocalName() + "] запущен (" + warehouse.x() + "; " + warehouse.y()
                + "), координатор, заявок: " + orders.size());
    }

    @Override
    protected void takeDown() {
        // Deregister from the yellow pages
        try {
            DFService.deregister(this);
        }
        catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }

    /**
     * Готовы ли начать этап 1: на связи все магазины, у которых есть заявки, машины найдены в DF
     * и их число не меняется несколько проверок подряд (значит, клиент с машинами запустился целиком).
     * Если ждём слишком долго — начинаем с теми, кто есть.
     */
    private boolean isEveryoneReady(int check) {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("truck");
            template.addServices(sd);
            trucks = DFService.search(this, template);
        } catch (FIPAException e) {
            e.printStackTrace();
            return false;
        }
        stableChecks = (trucks.length > 0 && trucks.length == lastTruckCount) ? stableChecks + 1 : 0;
        lastTruckCount = trucks.length;

        Set<String> needStores = new HashSet<>();
        orders.forEach(o -> needStores.add(o.store()));
        long storesOnline = needStores.stream().filter(stores::containsKey).count();

        boolean ready = storesOnline == needStores.size() && stableChecks >= STABLE_CHECKS;
        if (!ready && check >= MAX_READY_CHECKS && trucks.length > 0) {
            System.out.println("[" + getLocalName() + "] ждали слишком долго — начинаем с теми, кто на связи");
            return true;
        }
        String status = "магазины " + storesOnline + "/" + needStores.size() + ", машин " + trucks.length;
        if (!ready && !status.equals(lastStatus)) {
            System.out.println("[" + getLocalName() + "] жду участников: " + status);
            lastStatus = status;
        }
        return ready;
    }

    /**
     * Раунд этапа 1: каждую заявку рассылаем всем машинам (CFP).
     * Номер раунда входит в тему ("order-З-1#2"), чтобы ответы разных раундов не путались.
     */
    private void startRound(List<Order> list) {
        round++;
        assignedAtRoundStart = assigned.size();
        waiting.clear();
        System.out.println("[" + getLocalName() + "] раунд " + round + ": рассылаю заявок — " + list.size());

        for (Order o : list) {
            Store s = stores.get(o.store());
            if (s == null) {
                System.out.println("[" + getLocalName() + "] " + o.store() + " не на связи — заявка " + o.id() + " не отправлена");
                continue;
            }
            ACLMessage msg = new ACLMessage(ACLMessage.CFP);
            for (DFAgentDescription t : trucks) {
                msg.addReceiver(t.getName());          // все машины
            }
            msg.addReplyTo(storeAids.get(o.store()));  // отвечать магазину
            msg.setConversationId("order-" + o.id() + "#" + round);
            try {
                msg.setContentObject(new OrderRequest(o, s));
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }
            send(msg);
            waiting.add(o.id());
        }
        if (waiting.isEmpty()) {
            endRound();   // ничего не разослали — ждать нечего
        }
    }

    /** Итог по заявке от магазина: INFORM — назначена, FAILURE — не назначена. */
    private void handleResult(ACLMessage msg) {
        OrderResult r;
        try {
            r = (OrderResult) msg.getContentObject();
        } catch (UnreadableException e) {
            e.printStackTrace();
            return;
        }
        if (msg.getPerformative() == ACLMessage.INFORM && r.trip() != null) {
            assigned.put(r.orderId(), r.trip());
        }
        waiting.remove(r.orderId());
        if (waiting.isEmpty()) {
            endRound();
        }
    }

    /**
     * Все магазины раунда сообщили итог. Если есть нераспределённые заявки и в этом раунде
     * был прогресс — повторный раунд только для них (у машин уже другие расписания, они могут
     * предложить другое время). Иначе этап 1 закончен.
     */
    private void endRound() {
        List<Order> left = new ArrayList<>();
        for (Order o : orders) {
            if (!assigned.containsKey(o.id()) && stores.containsKey(o.store())) {
                left.add(o);
            }
        }
        boolean progress = assigned.size() > assignedAtRoundStart;
        System.out.println("[" + getLocalName() + "] раунд " + round + " завершён: назначено " + assigned.size()
                + " из " + orders.size());

        if (!left.isEmpty() && progress && round < MAX_ROUNDS) {
            startRound(left);
        } else {
            finishStage1();
        }
    }

    /**
     * Условие окончания этапа 1: у каждой заявки есть машина либо очередной раунд ничего не дал.
     * Склад собирает план и делает КОНТРОЛЬНУЮ проверку: у каждой машины рейсы не пересекаются
     * и груз помещается. Сами конфликты предотвращают машины при бронировании.
     */
    private void finishStage1() {
        System.out.println("\n========== ЭТАП 1 завершён: первоначальный план ==========");
        Map<String, List<Trip>> byTruck = new LinkedHashMap<>();
        for (Trip t : assigned.values()) {
            byTruck.computeIfAbsent(t.truck().name(), k -> new ArrayList<>()).add(t);
        }
        stage1Cost = printPlan(byTruck);
        byTruck.forEach((truck, trips) -> stage1Plan.put(truck, new ArrayList<>(trips)));   // копия для отчёта
        System.out.printf("Назначено %d из %d, раундов: %d, затраты: %.0f руб.%n",
                assigned.size(), orders.size(), round, stage1Cost);
        System.out.println(controlCheck(byTruck));

        System.out.println("\n========== ЭТАП 2: улучшение плана ==========");
        startImproveRound();
    }

    // ------------------------------------------------------------------ этап 2

    /** Раунд улучшения: всем машинам — «попробуйте отдать невыгодные заявки». */
    private void startImproveRound() {
        improveRound++;
        improveReports = 0;
        transfersInRound = 0;
        System.out.println("[" + getLocalName() + "] раунд улучшения " + improveRound);
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        for (DFAgentDescription t : trucks) {
            msg.addReceiver(t.getName());
        }
        msg.setConversationId("improve");
        send(msg);
    }

    /**
     * Итог раунда от машины. Когда отчитались все: УСЛОВИЕ ОСТАНОВКИ этапа 2 —
     * за раунд ни одна машина ничего не передала (или исчерпан лимит раундов).
     */
    private void handleImproveDone(ACLMessage msg) {
        try {
            transfersInRound += (Integer) msg.getContentObject();
        } catch (UnreadableException e) {
            e.printStackTrace();
        }
        improveReports++;
        if (improveReports < trucks.length) {
            return;
        }
        transfersTotal += transfersInRound;
        System.out.println("[" + getLocalName() + "] раунд улучшения " + improveRound + " завершён: передано заявок — "
                + transfersInRound);
        if (transfersInRound > 0 && improveRound < MAX_IMPROVE_ROUNDS) {
            startImproveRound();
        } else {
            requestSchedules();
        }
    }

    /** Улучшение закончено — запрашиваем у машин итоговые расписания. */
    private void requestSchedules() {
        finalSchedules.clear();
        ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
        for (DFAgentDescription t : trucks) {
            msg.addReceiver(t.getName());
        }
        msg.setConversationId("schedule");
        send(msg);
    }

    @SuppressWarnings("unchecked")
    private void handleSchedule(ACLMessage msg) {
        try {
            finalSchedules.put(msg.getSender().getLocalName(), (List<Trip>) msg.getContentObject());
        } catch (UnreadableException e) {
            e.printStackTrace();
        }
        if (finalSchedules.size() == trucks.length) {
            finishStage2();
        }
    }

    private void finishStage2() {
        System.out.println("\n========== ЭТАП 2 завершён: итоговый план ==========");
        Map<String, List<Trip>> byTruck = new LinkedHashMap<>();
        for (Map.Entry<String, List<Trip>> e : finalSchedules.entrySet()) {
            if (!e.getValue().isEmpty()) {
                byTruck.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        double total = printPlan(byTruck);
        long trips = byTruck.values().stream().mapToLong(List::size).sum();
        System.out.printf("Рейсов: %d, машин задействовано: %d, передано заявок: %d, раундов улучшения: %d%n",
                trips, byTruck.size(), transfersTotal, improveRound);
        System.out.printf("Затраты: этап 1 — %.0f руб., после улучшения — %.0f руб. (экономия %.0f руб.)%n",
                stage1Cost, total, stage1Cost - total);
        String check = controlCheck(byTruck);
        System.out.println(check);
        writeReport(byTruck, check);
        System.out.println("[" + getLocalName() + "] работа завершена");
    }

    /** Требование 3: результат работы — в файл, удобный для чтения (HTML, открывается в браузере). */
    private void writeReport(Map<String, List<Trip>> finalPlan, String check) {
        ReportWriter.Summary summary = new ReportWriter.Summary(round, improveRound, transfersTotal, check,
                warehouse.workFrom(), warehouse.workTo());
        try {
            Path file = ReportWriter.write(REPORT_FILE, orders, stage1Plan, finalPlan, summary);
            System.out.println("[" + getLocalName() + "] отчёт сохранён: " + file);
        } catch (IOException e) {
            System.out.println("[" + getLocalName() + "] не удалось записать отчёт: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ общее

    /** Печать плана: склад → его машины → рейсы; возвращает суммарные затраты. */
    private double printPlan(Map<String, List<Trip>> byTruck) {
        // группируем машины по складам
        Map<String, Map<String, List<Trip>>> byWarehouse = new java.util.TreeMap<>();
        for (Map.Entry<String, List<Trip>> e : byTruck.entrySet()) {
            String wh = e.getValue().get(0).truck().warehouse();
            byWarehouse.computeIfAbsent(wh, k -> new java.util.TreeMap<>()).put(e.getKey(), e.getValue());
        }
        double total = 0;
        Set<String> delivered = new HashSet<>();
        for (Map.Entry<String, Map<String, List<Trip>>> w : byWarehouse.entrySet()) {
            System.out.println(w.getKey() + ":");
            for (Map.Entry<String, List<Trip>> e : w.getValue().entrySet()) {
                e.getValue().sort(Comparator.comparing(Trip::start));
                System.out.println("  " + e.getKey() + ":");
                for (Trip t : e.getValue()) {
                    StringBuilder stops = new StringBuilder();
                    for (var stop : t.stops()) {
                        stops.append(String.format("  %s %s %s", stop.order().id(), stop.store().name(), stop.arrival()));
                        delivered.add(stop.order().id());
                    }
                    System.out.printf("     %s–%s |%s | %.0f кг, %.1f км, %.0f руб.%n",
                            t.start(), t.end(), stops, t.loadKg(), t.distanceKm(), t.cost());
                    total += t.cost();
                }
            }
        }
        for (Order o : orders) {
            if (!delivered.contains(o.id())) {
                System.out.println("НЕ НАЗНАЧЕНА: " + o.id() + " (" + o.store() + ")");
            }
        }
        return total;
    }

    /**
     * КОНТРОЛЬНАЯ проверка плана: у каждой машины рейсы не пересекаются и груз помещается.
     * Сами конфликты предотвращают машины при бронировании — здесь только контроль.
     */
    private String controlCheck(Map<String, List<Trip>> byTruck) {
        List<String> problems = new ArrayList<>();
        for (List<Trip> trips : byTruck.values()) {
            for (int i = 0; i < trips.size(); i++) {
                Trip a = trips.get(i);
                if (a.loadKg() > a.truck().capacityKg()) {
                    problems.add(a.truck().name() + ": перегруз " + a.loadKg() + " кг");
                }
                for (int j = i + 1; j < trips.size(); j++) {
                    if (a.overlaps(trips.get(j))) {
                        problems.add(a.truck().name() + ": пересекаются " + a.start() + "–" + a.end()
                                + " и " + trips.get(j).start() + "–" + trips.get(j).end());
                    }
                }
            }
        }
        return problems.isEmpty()
                ? "Контрольная проверка: пересечений и перегруза нет"
                : "Контрольная проверка: ОШИБКИ " + problems;
    }


    private void registerInDF(String... types) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        for (String type : types) {
            ServiceDescription sd = new ServiceDescription();
            sd.setType(type);
            sd.setName(getLocalName());
            dfd.addServices(sd);
        }
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
}
