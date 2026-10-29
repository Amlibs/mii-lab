package delivery.agents;

import delivery.logic.RouteCalc;
import delivery.model.Order;
import delivery.model.Stop;
import delivery.model.Store;
import delivery.model.Trip;
import delivery.model.Truck;
import delivery.model.Warehouse;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.UnreadableException;

import java.io.IOException;
import java.io.Serializable;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class TruckAgent extends Agent {
    /** Сколько ждём ответы других машин на предложение забрать заявку, мс. */
    private static final long TRANSFER_TIMEOUT_MS = 2000;

    private Truck truck;
    private final List<Trip> schedule = new ArrayList<>();
    /**
     * Свой склад: отсюда выезжаем и сюда возвращаемся. Данные склада присылает сам склад
     * в ответ на наш «привет» — так машине не нужен файл складов (он лежит на сервере).
     */
    private Warehouse warehouse;

    // ---- этап 2: состояние «отдающей» стороны ----
    /** Адрес склада, которому отчитываемся об итогах раунда улучшения. */
    private AID warehouseAid;
    /** Рейсы, которые в этом раунде ещё попробуем отдать (от дорогого к дешёвому). */
    private final List<Trip> giveQueue = new ArrayList<>();
    /** Рейс, который сейчас пытаемся отдать (его не трогаем как «принимающие»). */
    private Trip givingTrip;
    /** Тема текущей попытки отдать заявку. */
    private String givingConv;
    /** Ответы других машин на текущую попытку. */
    private final List<ACLMessage> giveReplies = new ArrayList<>();
    /** Предложения, отсортированные по добавке, — запасные на случай отказа при повторной проверке. */
    private final List<ACLMessage> giveOffers = new ArrayList<>();
    /** Сколько машин опросили в текущей попытке. */
    private int giveAsked;
    /** Выбор по текущей попытке уже сделан (чтобы таймаут не выбрал второй раз). */
    private boolean giveDecided;
    /** Удалось ли отдать хоть одну заявку в этом раунде. */
    private int transfersThisRound;
    private int attemptNo = 0;

    @Override
    protected void setup() {
        truck = (Truck) getArguments()[0];
        registerInDF("truck");
        helloToWarehouse();
        // если свой склад ещё не запущен (сервер стартует позже) — повторяем «привет», пока не ответит
        addBehaviour(new TickerBehaviour(this, 3000) {
            @Override
            protected void onTick() {
                if (warehouse != null) {
                    stop();
                } else {
                    System.out.println("[" + getLocalName() + "] склад " + truck.warehouse() + " пока не отвечает, повторяю");
                    helloToWarehouse();
                }
            }
        });

        // Одно поведение принимает всё, а разбираем по теме разговора:
        //   "truck-hello"  — свой склад прислал свои данные;
        //   "order-..."    — этап 1 (заявки магазинов);
        //   "improve"      — склад начинает раунд улучшения;
        //   "transfer-..." — этап 2 (передача заявок между машинами);
        //   "schedule"     — склад просит итоговое расписание.
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive();
                if (msg == null) { block(); return; }
                String conv = msg.getConversationId() == null ? "" : msg.getConversationId();
                int p = msg.getPerformative();

                if (conv.equals("truck-hello")) {
                    if (p == ACLMessage.INFORM) receiveWarehouse(msg);
                    // иначе это отказ платформы «агент не найден» — склад ещё не запущен, повторим позже
                } else if (conv.startsWith("order-")) {
                    if (p == ACLMessage.CFP) handleOrder(msg);
                    else if (p == ACLMessage.ACCEPT_PROPOSAL) handleAccept(msg);
                    // REJECT_PROPOSAL: мы ничего не бронировали — делать нечего
                } else if (conv.equals("improve")) {
                    startImproveRound(msg);
                } else if (conv.startsWith("transfer-")) {
                    if (conv.equals(givingConv)) {
                        handleGiveReply(msg);          // ответы на НАШЕ предложение отдать заявку
                    } else if (p == ACLMessage.CFP) {
                        handleTransferCfp(msg);        // другая машина предлагает НАМ взять заявку
                    } else if (p == ACLMessage.ACCEPT_PROPOSAL) {
                        handleTransferAccept(msg);     // другая машина выбрала НАС
                    }
                } else if (conv.equals("schedule")) {
                    sendSchedule(msg);
                }
            }
        });
        System.out.println("[" + getLocalName() + "] запущен (" + truck.warehouse() + "), "
                + truck.capacityKg() + " кг, " + truck.speedKmh() + " км/ч, " + truck.costPerKm() + " руб./км");
    }

    /** «Привет» своему складу (по имени из trucks.json) — он внесёт нас в свой пул и пришлёт свои данные. */
    private void helloToWarehouse() {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(new AID(truck.warehouse(), AID.ISLOCALNAME));
        msg.setConversationId("truck-hello");
        setContent(msg, truck);
        send(msg);
    }

    private void receiveWarehouse(ACLMessage msg) {
        try {
            warehouse = (Warehouse) msg.getContentObject();
            System.out.println("[" + getLocalName() + "] приписан к " + warehouse.name()
                    + " (" + warehouse.x() + "; " + warehouse.y() + ")");
        } catch (UnreadableException e) {
            e.printStackTrace();
        }
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

    // =====================================================================================
    //                                       ЭТАП 1
    // =====================================================================================

    /**
     * Этап 1: пришла заявка (CFP). Ищем время для рейса с учётом своего расписания
     * и отвечаем МАГАЗИНУ (адрес в reply-to): PROPOSE с рейсом или REFUSE.
     */
    private void handleOrder(ACLMessage msg) {
        OrderRequest req;
        try {
            req = (OrderRequest) msg.getContentObject();
        } catch (UnreadableException e) {
            e.printStackTrace();
            return;
        }
        // рейс считаем от СВОЕГО склада; если склад ещё не прислал данные — предложить нечего
        Optional<Trip> trip = warehouse == null ? Optional.empty()
                : findTrip(warehouse, req.store(), req.order());

        ACLMessage reply = msg.createReply();   // получатель — из reply-to (магазин), тема — та же
        if (trip.isPresent()) {
            reply.setPerformative(ACLMessage.PROPOSE);
            setContent(reply, trip.get());
            System.out.println("[" + getLocalName() + "] " + req.order().id() + " (" + req.store().name()
                    + "): предлагаю приезд " + trip.get().stops().get(0).arrival()
                    + ", рейс " + trip.get().start() + "–" + trip.get().end()
                    + ", " + Math.round(trip.get().cost()) + " руб.");
        } else {
            reply.setPerformative(ACLMessage.REFUSE);
            System.out.println("[" + getLocalName() + "] " + req.order().id() + " (" + req.store().name()
                    + "): отказ — не успеваю или не помещается");
        }
        send(reply);
    }

    /**
     * Найти рейс, который не пересекается с уже забронированными.
     * Сначала пробуем самое раннее время, потом — сразу после возвращения из каждого
     * своего рейса (машина вернулась на склад и снова свободна).
     */
    private Optional<Trip> findTrip(Warehouse w, Store s, Order o) {
        List<LocalTime> candidates = new ArrayList<>();
        candidates.add(null);                                   // как можно раньше
        schedule.stream()
                .sorted(Comparator.comparing(Trip::start))
                .forEach(t -> candidates.add(t.end()));         // после каждого своего рейса

        for (LocalTime notBefore : candidates) {
            Optional<Trip> trip = RouteCalc.calcTrip(w, s, truck, o, notBefore);
            if (trip.isPresent() && !RouteCalc.haveOverlaps(schedule, trip.get())) {
                return trip;
            }
        }
        return Optional.empty();
    }

    /**
     * Магазин выбрал наше предложение. ЗДЕСЬ ПРЕДОТВРАЩАЕТСЯ КОНФЛИКТ:
     * с момента предложения мы могли забронировать другой рейс на это же время,
     * поэтому проверяем расписание ещё раз. Свободно — бронируем и подтверждаем (INFORM),
     * занято — отказ (FAILURE), магазин возьмёт следующее предложение.
     */
    private void handleAccept(ACLMessage msg) {
        Trip trip;
        try {
            trip = (Trip) msg.getContentObject();
        } catch (UnreadableException e) {
            e.printStackTrace();
            return;
        }
        String orderId = trip.stops().get(0).order().id();

        ACLMessage reply = msg.createReply();
        if (!RouteCalc.haveOverlaps(schedule, trip)) {
            schedule.add(trip);
            reply.setPerformative(ACLMessage.INFORM);
            setContent(reply, trip);
            System.out.println("[" + getLocalName() + "] " + orderId + ": забронировал "
                    + trip.start() + "–" + trip.end() + " (рейсов в расписании: " + schedule.size() + ")");
        } else {
            reply.setPerformative(ACLMessage.FAILURE);
            System.out.println("[" + getLocalName() + "] " + orderId + ": КОНФЛИКТ — интервал "
                    + trip.start() + "–" + trip.end() + " уже занят, отказ");
        }
        send(reply);
    }

    // =====================================================================================
    //                         ЭТАП 2: отдающая сторона («не выгодно — отдам»)
    // =====================================================================================

    /**
     * Склад начал раунд улучшения. Пробуем по очереди отдать свои отдельные рейсы
     * (с одним магазином), начиная с самого дорогого: если другая машина довезёт эту заявку
     * попутно дешевле, чем стоит наш отдельный рейс, — передаём.
     */
    private void startImproveRound(ACLMessage msg) {
        warehouseAid = msg.getSender();
        transfersThisRound = 0;
        giveQueue.clear();
        schedule.stream()
                .filter(t -> t.stops().size() == 1)
                .sorted(Comparator.comparingDouble(Trip::cost).reversed())
                .forEach(giveQueue::add);
        tryNextGive();
    }

    /** Следующая попытка отдать рейс; когда пробовать больше нечего — отчёт складу. */
    private void tryNextGive() {
        givingTrip = null;
        givingConv = null;

        // рейс мог измениться, пока мы пробовали другие (к нему подсадили чужую заявку)
        while (!giveQueue.isEmpty() && !schedule.contains(giveQueue.get(0))) {
            giveQueue.remove(0);
        }
        if (giveQueue.isEmpty()) {
            reportRoundDone();
            return;
        }

        List<AID> others = otherTrucks();
        if (others.isEmpty() || warehouse == null) {
            giveQueue.clear();
            reportRoundDone();
            return;
        }

        givingTrip = giveQueue.remove(0);
        Stop stop = givingTrip.stops().get(0);
        givingConv = "transfer-" + getLocalName() + "-" + (++attemptNo);
        giveReplies.clear();
        giveOffers.clear();
        giveAsked = others.size();
        giveDecided = false;

        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        others.forEach(cfp::addReceiver);
        cfp.setConversationId(givingConv);
        setContent(cfp, new TransferRequest(stop.order(), stop.store(), givingTrip.cost()));
        send(cfp);
        System.out.println("[" + getLocalName() + "] пробую отдать " + stop.order().id()
                + " (мой рейс " + givingTrip.start() + "–" + givingTrip.end() + ", "
                + Math.round(givingTrip.cost()) + " руб.)");

        // страховка: если кто-то не ответит — решаем по тому, что пришло
        String conv = givingConv;
        addBehaviour(new WakerBehaviour(this, TRANSFER_TIMEOUT_MS) {
            @Override
            protected void onWake() {
                if (conv.equals(givingConv) && !giveDecided) {
                    decideGive();
                }
            }
        });
    }

    /** Ответы на наше предложение: PROPOSE/REFUSE от машин, потом INFORM/FAILURE от выбранной. */
    private void handleGiveReply(ACLMessage msg) {
        switch (msg.getPerformative()) {
            case ACLMessage.PROPOSE, ACLMessage.REFUSE -> {
                if (giveDecided) return;            // опоздавший ответ
                giveReplies.add(msg);
                if (giveReplies.size() >= giveAsked) {
                    decideGive();
                }
            }
            case ACLMessage.INFORM -> {
                // принимающая машина подтвердила: заявка теперь у неё, наш рейс отменяется
                schedule.remove(givingTrip);
                transfersThisRound++;
                System.out.println("[" + getLocalName() + "] " + givingTrip.stops().get(0).order().id()
                        + " передана " + msg.getSender().getLocalName() + ", мой рейс "
                        + givingTrip.start() + "–" + givingTrip.end() + " отменён (−"
                        + Math.round(givingTrip.cost()) + " руб.)");
                tryNextGive();
            }
            case ACLMessage.FAILURE -> {
                // при повторной проверке у неё уже не вышло — пробуем следующее предложение
                acceptNextOffer();
            }
            default -> { }
        }
    }

    /** Выбор: самое дешёвое предложение, и только если оно дешевле нашего отдельного рейса. */
    private void decideGive() {
        giveDecided = true;
        for (ACLMessage m : giveReplies) {
            if (m.getPerformative() == ACLMessage.PROPOSE
                    && offerOf(m).extraCost() < givingTrip.cost()) {
                giveOffers.add(m);
            }
        }
        giveOffers.sort(Comparator.comparingDouble(m -> offerOf(m).extraCost()));
        acceptNextOffer();
    }

    private void acceptNextOffer() {
        if (giveOffers.isEmpty()) {
            System.out.println("[" + getLocalName() + "] " + givingTrip.stops().get(0).order().id()
                    + ": выгодных предложений нет — оставляю себе");
            tryNextGive();
            return;
        }
        ACLMessage best = giveOffers.remove(0);
        Stop stop = givingTrip.stops().get(0);
        ACLMessage accept = best.createReply();
        accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
        setContent(accept, new TransferRequest(stop.order(), stop.store(), givingTrip.cost()));
        send(accept);
        System.out.println("[" + getLocalName() + "] " + stop.order().id() + ": отдаю "
                + best.getSender().getLocalName() + " за +" + Math.round(offerOf(best).extraCost())
                + " руб. (мой рейс стоит " + Math.round(givingTrip.cost()) + " руб.)");
    }

    private void reportRoundDone() {
        if (warehouseAid == null) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(warehouseAid);
        msg.setConversationId("improve-done");
        setContent(msg, transfersThisRound);
        send(msg);
    }

    // =====================================================================================
    //                       ЭТАП 2: принимающая сторона («возьму попутно»)
    // =====================================================================================

    /** Лучший способ подсадить заявку в один из наших рейсов. */
    private record Insertion(Trip oldTrip, Trip newTrip, double extraCost) { }

    /**
     * Пробуем добавить заявку попутно в каждый наш рейс (кроме того, который сами сейчас отдаём),
     * проверяем, что новый рейс не пересекается с ОСТАЛЬНЫМИ нашими рейсами, берём самый дешёвый.
     */
    private Optional<Insertion> bestInsertion(TransferRequest req) {
        if (warehouse == null) {
            return Optional.empty();
        }
        Insertion best = null;
        for (Trip t : schedule) {
            if (t.equals(givingTrip)) continue;
            Optional<Trip> nt = RouteCalc.addToTrip(warehouse, t, req.store(), req.order());   // от своего склада
            if (nt.isEmpty()) continue;
            List<Trip> others = new ArrayList<>(schedule);
            others.remove(t);
            if (RouteCalc.haveOverlaps(others, nt.get())) continue;
            double extra = nt.get().cost() - t.cost();
            if (best == null || extra < best.extraCost()) {
                best = new Insertion(t, nt.get(), extra);
            }
        }
        return Optional.ofNullable(best);
    }

    /** Другая машина предлагает забрать заявку: отвечаем добавкой к нашему рейсу или отказом. */
    private void handleTransferCfp(ACLMessage msg) {
        TransferRequest req;
        try {
            req = (TransferRequest) msg.getContentObject();
        } catch (UnreadableException e) {
            e.printStackTrace();
            return;
        }
        ACLMessage reply = msg.createReply();
        Optional<Insertion> ins = bestInsertion(req);
        if (ins.isPresent()) {
            reply.setPerformative(ACLMessage.PROPOSE);
            setContent(reply, new TransferOffer(ins.get().newTrip(), ins.get().extraCost()));
            System.out.println("[" + getLocalName() + "] " + req.order().id() + ": возьму попутно за +"
                    + Math.round(ins.get().extraCost()) + " руб.");
        } else {
            reply.setPerformative(ACLMessage.REFUSE);
        }
        send(reply);
    }

    /**
     * Нас выбрали. ПОВТОРНАЯ ПРОВЕРКА (предотвращение конфликта на этапе 2): пока шли переговоры,
     * в наш рейс могли подсадить другую заявку. Пересчитываем; если всё ещё помещаемся и это
     * всё ещё выгодно — заменяем рейс в расписании, подтверждаем и сообщаем магазинам новое время.
     */
    private void handleTransferAccept(ACLMessage msg) {
        TransferRequest req;
        try {
            req = (TransferRequest) msg.getContentObject();
        } catch (UnreadableException e) {
            e.printStackTrace();
            return;
        }
        ACLMessage reply = msg.createReply();
        Optional<Insertion> ins = bestInsertion(req);
        if (ins.isPresent() && ins.get().extraCost() < req.giverCost()) {
            schedule.remove(ins.get().oldTrip());
            schedule.add(ins.get().newTrip());
            reply.setPerformative(ACLMessage.INFORM);
            System.out.println("[" + getLocalName() + "] " + req.order().id() + ": беру попутно, рейс теперь "
                    + ins.get().newTrip().start() + "–" + ins.get().newTrip().end() + ", магазинов: "
                    + ins.get().newTrip().stops().size());
            notifyStores(ins.get().newTrip());
        } else {
            reply.setPerformative(ACLMessage.FAILURE);
            System.out.println("[" + getLocalName() + "] " + req.order().id()
                    + ": КОНФЛИКТ — после повторной проверки взять уже не могу");
        }
        send(reply);
    }

    /** Сообщаем каждому магазину рейса, кто и когда к нему теперь приедет. */
    private void notifyStores(Trip trip) {
        for (Stop s : trip.stops()) {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(new AID(s.store().name(), AID.ISLOCALNAME));
            msg.setConversationId("transfer-notice");
            setContent(msg, s);
            send(msg);
        }
    }

    // =====================================================================================
    //                                    Итог
    // =====================================================================================

    /** Склад просит итоговое расписание для плана и отчёта. */
    private void sendSchedule(ACLMessage msg) {
        ACLMessage reply = msg.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        setContent(reply, new ArrayList<>(schedule));
        send(reply);
    }

    // =====================================================================================

    /** Другие машины из DF (кроме нас). */
    private List<AID> otherTrucks() {
        List<AID> res = new ArrayList<>();
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("truck");
        template.addServices(sd);
        try {
            for (DFAgentDescription d : DFService.search(this, template)) {
                if (!d.getName().equals(getAID())) {
                    res.add(d.getName());
                }
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        return res;
    }

    private TransferOffer offerOf(ACLMessage m) {
        try {
            return (TransferOffer) m.getContentObject();
        } catch (UnreadableException e) {
            throw new IllegalStateException("не удалось прочитать предложение", e);
        }
    }

    private static void setContent(ACLMessage msg, Serializable content) {
        try {
            msg.setContentObject(content);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось упаковать сообщение", e);
        }
    }

    private void registerInDF(String s) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(s);
        sd.setName(getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
}
