package delivery.agents;

import delivery.model.Stop;
import delivery.model.Store;
import delivery.model.Trip;
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
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class StoreAgent extends Agent {
    /** Сколько ждём ответы машин по заявке после первого ответа, мс. */
    private static final long REPLY_TIMEOUT_MS = 3000;

    private Store store;

    /** Ответы машин (PROPOSE и REFUSE) по каждой заявке. Ключ — тема, например "order-З-1". */
    private final Map<String, List<ACLMessage>> replies = new HashMap<>();
    /** Заявки, по которым выбор уже сделан (чтобы таймаут не выбрал второй раз). */
    private final Set<String> decided = new HashSet<>();
    /** Оставшиеся предложения по заявке, от дешёвого к дорогому — запасные на случай отказа машины. */
    private final Map<String, List<ACLMessage>> backupOffers = new HashMap<>();
    /** Сколько машин в системе (ищем в DF один раз). */
    private int truckCount = -1;
    /** Адрес склада — запоминаем при «привете», чтобы потом сообщать ему итог. */
    private AID warehouseAid;

    @Override
    protected void setup() {
        store = (Store) getArguments()[0];
        registerInDF("store");
        System.out.println("[" + getLocalName() + "] запущен (" + store.x() + "; " + store.y() + ")");
        // «Привет» координатору; если сервер ещё не запущен — повторяем каждые 2 с
        if (!helloToWarehouse()) {
            addBehaviour(new TickerBehaviour(this, 2000) {
                @Override
                protected void onTick() {
                    if (helloToWarehouse()) {
                        stop();
                    }
                }
            });
        }

        // Сообщения от машин по нашим заявкам (тема "order-..."):
        // PROPOSE/REFUSE — ответы на заявку, INFORM/FAILURE — бронь подтверждена / конфликт
        MessageTemplate fromTrucks = new MessageTemplate(
                m -> m.getConversationId() != null && m.getConversationId().startsWith("order-"));
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(fromTrucks);
                if (msg == null) {block(); return;}
                switch (msg.getPerformative()) {
                    case ACLMessage.PROPOSE, ACLMessage.REFUSE -> handleReply(msg);
                    case ACLMessage.INFORM -> handleConfirm(msg);
                    case ACLMessage.FAILURE -> handleFailure(msg);
                    default -> { }
                }
            }
        });

        // Этап 2: заявку передали другой машине (или в рейс подсадили соседа) — новое время приезда
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(MessageTemplate.MatchConversationId("transfer-notice"));
                if (msg == null) {block(); return;}
                try {
                    Stop stop = (Stop) msg.getContentObject();
                    System.out.println("[" + getLocalName() + "] " + stop.order().id() + ": теперь везёт "
                            + msg.getSender().getLocalName() + ", приезд " + stop.arrival());
                } catch (UnreadableException e) {
                    e.printStackTrace();
                }
            }
        });
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

    /** @return true — координатор найден и «привет» отправлен */
    private boolean helloToWarehouse() {
        // 1. Найти склад-координатор в «жёлтых страницах» (складов может быть несколько)
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("coordinator");
        template.addServices(sd);
        try {
            DFAgentDescription[] found = DFService.search(this, template);
            if (found.length == 0) {
                System.out.println("[" + getLocalName() + "] склад-координатор пока не найден, жду");
                return false;
            }
            AID warehouse = found[0].getName();   // адрес склада
            warehouseAid = warehouse;             // запоминаем — понадобится для итога

            // 2. Собрать письмо
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(warehouse);
            msg.setConversationId("hello");
            msg.setContentObject(store);          // кладём свой Store целиком

            // 3. Отправить
            send(msg);
            return true;
        } catch (FIPAException | IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Пришёл ответ машины по одной из наших заявок. Копим ответы по заявке;
     * выбираем, когда ответили все машины, или по таймауту — что наступит раньше.
     */
    private void handleReply(ACLMessage msg) {
        String conv = msg.getConversationId();          // "order-З-1"
        if (decided.contains(conv)) {
            // ответ опоздал — выбор по этой заявке уже сделан по таймауту
            System.out.println("[" + getLocalName() + "] " + orderId(conv) + ": ответ от "
                    + msg.getSender().getLocalName() + " пришёл после выбора — пропускаю");
            return;
        }

        List<ACLMessage> list = replies.computeIfAbsent(conv, k -> new ArrayList<>());
        list.add(msg);

        // первый ответ по заявке — заводим таймаут
        if (list.size() == 1) {
            addBehaviour(new WakerBehaviour(this, REPLY_TIMEOUT_MS) {
                @Override
                protected void onWake() {
                    if (!decided.contains(conv)) {
                        System.out.println("[" + getLocalName() + "] " + orderId(conv)
                                + ": таймаут, выбираю из того, что пришло");
                        choose(conv);
                    }
                }
            });
        }

        // ответили все машины — выбираем сразу
        if (list.size() >= truckCount()) {
            choose(conv);
        }
    }

    /**
     * Выбор по заявке: из предложений (PROPOSE) берём самое дешёвое и отвечаем ему ACCEPT_PROPOSAL,
     * в содержимое кладём выбранный рейс — машина забронирует именно его.
     * Остальные предложения сохраняем отсортированными: если выбранная машина откажет
     * (интервал уже занят), возьмём следующее. Отказы остальным (REJECT_PROPOSAL) —
     * после подтверждения брони, на следующем шаге.
     */
    private void choose(String conv) {
        decided.add(conv);

        List<ACLMessage> offers = new ArrayList<>();
        for (ACLMessage m : replies.get(conv)) {
            if (m.getPerformative() == ACLMessage.PROPOSE) {
                offers.add(m);
            }
        }
        if (offers.isEmpty()) {
            System.out.println("[" + getLocalName() + "] " + orderId(conv) + ": нет ни одного предложения");
            reportToWarehouse(conv, null);
            return;
        }
        offers.sort((a, b) -> Double.compare(tripOf(a).cost(), tripOf(b).cost()));

        ACLMessage best = offers.remove(0);
        backupOffers.put(conv, offers);
        sendAccept(conv, best);
        System.out.println("[" + getLocalName() + "] " + orderId(conv) + ": выбрал "
                + best.getSender().getLocalName() + " — приезд " + tripOf(best).stops().get(0).arrival()
                + ", " + Math.round(tripOf(best).cost()) + " руб. (предложений: " + (offers.size() + 1)
                + ", ответов: " + replies.get(conv).size() + ")");
    }

    /** ACCEPT_PROPOSAL выбранной машине; в содержимом — рейс, который она должна забронировать. */
    private void sendAccept(String conv, ACLMessage proposal) {
        ACLMessage accept = proposal.createReply();   // уйдёт этой машине, тема та же
        accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
        try {
            accept.setContentObject(tripOf(proposal));
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        send(accept);
    }

    /**
     * Машина подтвердила бронь (INFORM) — переговоры по заявке завершены взаимным подтверждением.
     * Остальным машинам — отказ (REJECT_PROPOSAL), складу — итог.
     */
    private void handleConfirm(ACLMessage msg) {
        String conv = msg.getConversationId();
        Trip trip;
        try {
            trip = (Trip) msg.getContentObject();
        } catch (UnreadableException e) {
            e.printStackTrace();
            return;
        }
        System.out.println("[" + getLocalName() + "] " + orderId(conv) + ": доставка назначена — "
                + msg.getSender().getLocalName() + ", приезд " + trip.stops().get(0).arrival());

        for (ACLMessage other : backupOffers.getOrDefault(conv, List.of())) {
            ACLMessage reject = other.createReply();
            reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
            send(reject);
        }
        backupOffers.remove(conv);
        reportToWarehouse(conv, trip);
    }

    /**
     * Машина отказала при повторной проверке (FAILURE): интервал уже занят другим магазином.
     * Берём следующее по цене предложение; если их больше нет — заявка не назначена.
     */
    private void handleFailure(ACLMessage msg) {
        String conv = msg.getConversationId();
        List<ACLMessage> rest = backupOffers.getOrDefault(conv, new ArrayList<>());
        if (rest.isEmpty()) {
            System.out.println("[" + getLocalName() + "] " + orderId(conv) + ": конфликт у "
                    + msg.getSender().getLocalName() + ", других предложений нет — не назначено");
            backupOffers.remove(conv);
            reportToWarehouse(conv, null);
            return;
        }
        ACLMessage next = rest.remove(0);
        sendAccept(conv, next);
        System.out.println("[" + getLocalName() + "] " + orderId(conv) + ": конфликт у "
                + msg.getSender().getLocalName() + " — беру следующее: " + next.getSender().getLocalName()
                + ", " + Math.round(tripOf(next).cost()) + " руб.");
    }

    /** Итог по заявке складу: INFORM — назначена (рейс в содержимом), FAILURE — не назначена. */
    private void reportToWarehouse(String conv, Trip trip) {
        if (warehouseAid == null) {
            System.out.println("[" + getLocalName() + "] склад неизвестен — итог не отправлен");
            return;
        }
        ACLMessage msg = new ACLMessage(trip != null ? ACLMessage.INFORM : ACLMessage.FAILURE);
        msg.addReceiver(warehouseAid);
        msg.setConversationId("result");
        try {
            msg.setContentObject(new OrderResult(orderId(conv), store.name(), trip));
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        send(msg);
    }

    /** Рейс из предложения машины. */
    private Trip tripOf(ACLMessage proposal) {
        try {
            return (Trip) proposal.getContentObject();
        } catch (UnreadableException e) {
            throw new IllegalStateException("не удалось прочитать рейс из предложения", e);
        }
    }

    /** Сколько машин в системе — ищем в DF при первом обращении. */
    private int truckCount() {
        if (truckCount < 0) {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("truck");
            template.addServices(sd);
            try {
                truckCount = DFService.search(this, template).length;
            } catch (FIPAException e) {
                e.printStackTrace();
                truckCount = Integer.MAX_VALUE;   // не знаем — тогда выбор только по таймауту
            }
        }
        return truckCount;
    }

    /** "order-З-1#2" → "З-1" (после # — номер раунда). */
    private static String orderId(String conv) {
        String s = conv.startsWith("order-") ? conv.substring("order-".length()) : conv;
        int hash = s.indexOf('#');
        return hash >= 0 ? s.substring(0, hash) : s;
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
