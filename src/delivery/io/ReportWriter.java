package delivery.io;

import delivery.model.Order;
import delivery.model.Stop;
import delivery.model.Trip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Итоговый отчёт в HTML-файл: открывается в любом браузере, удобно читать и печатать.
 * Содержит сводку, итоговый план по складам и машинам (таблица + шкала времени),
 * доставку по каждой заявке и сравнение с первоначальным планом этапа 1.
 */
public final class ReportWriter {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Сводные показатели работы системы.
     *
     * @param stage1Rounds   раундов этапа 1
     * @param improveRounds  раундов улучшения (этап 2)
     * @param transfers      сколько заявок передано на этапе 2
     * @param controlCheck   результат контрольной проверки итогового плана
     * @param dayFrom        начало шкалы времени (обычно открытие склада)
     * @param dayTo          конец шкалы времени (обычно закрытие склада)
     */
    public record Summary(int stage1Rounds, int improveRounds, int transfers, String controlCheck,
                          LocalTime dayFrom, LocalTime dayTo) {
    }

    private ReportWriter() {
    }

    /**
     * Записать отчёт.
     *
     * @param file      куда писать (например, report.html)
     * @param orders    пул заявок
     * @param stage1    план после этапа 1: машина → рейсы
     * @param finalPlan итоговый план после этапа 2: машина → рейсы
     * @return полный путь к записанному файлу
     */
    public static Path write(Path file, List<Order> orders, Map<String, List<Trip>> stage1,
                             Map<String, List<Trip>> finalPlan, Summary summary) throws IOException {
        StringBuilder h = new StringBuilder();
        double cost1 = totalCost(stage1);
        double cost2 = totalCost(finalPlan);
        int trips1 = tripCount(stage1);
        int trips2 = tripCount(finalPlan);
        double km2 = finalPlan.values().stream().flatMap(List::stream).mapToDouble(Trip::distanceKm).sum();

        // заявка → (рейс, остановка) в итоговом плане
        Map<String, Trip> tripOf = new HashMap<>();
        Map<String, Stop> stopOf = new HashMap<>();
        for (List<Trip> trips : finalPlan.values()) {
            for (Trip t : trips) {
                for (Stop s : t.stops()) {
                    tripOf.put(s.order().id(), t);
                    stopOf.put(s.order().id(), s);
                }
            }
        }
        long delivered = orders.stream().filter(o -> stopOf.containsKey(o.id())).count();

        h.append("""
                <!doctype html>
                <html lang="ru"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>План доставки</title>
                <style>
                  body { font-family: Arial, Helvetica, sans-serif; color: #1d1d1f; background: #fff;
                         margin: 24px auto; max-width: 1100px; padding: 0 16px; line-height: 1.4; }
                  h1 { margin-bottom: 4px; } h2 { margin-top: 32px; border-bottom: 2px solid #ddd; padding-bottom: 4px; }
                  h3 { margin: 20px 0 8px; }
                  .muted { color: #666; font-size: 14px; }
                  .cards { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0; }
                  .card { border: 1px solid #ddd; border-radius: 8px; padding: 10px 14px; min-width: 150px; }
                  .card b { display: block; font-size: 22px; }
                  .ok { color: #1a7f37; } .bad { color: #c62828; }
                  table { border-collapse: collapse; width: 100%; margin: 8px 0 16px; font-size: 14px; }
                  th, td { border: 1px solid #ddd; padding: 6px 8px; text-align: left; vertical-align: top; }
                  th { background: #f4f4f6; } td.num { text-align: right; white-space: nowrap; }
                  .timeline { position: relative; height: 28px; background: #f4f4f6; border-radius: 4px; margin: 2px 0 10px; }
                  .bar { position: absolute; top: 3px; height: 22px; background: #4a78c2; color: #fff; border-radius: 3px;
                         font-size: 11px; overflow: hidden; white-space: nowrap; padding: 0 4px; line-height: 22px; }
                  .scale { position: relative; height: 16px; font-size: 11px; color: #666; }
                  .scale span { position: absolute; transform: translateX(-50%); }
                  .tl-row { display: flex; align-items: center; gap: 8px; } .tl-name { width: 90px; font-size: 13px; }
                  .tl-wrap { flex: 1; }
                  @media print { body { margin: 0; } h2 { page-break-after: avoid; } }
                </style></head><body>
                """);

        h.append("<h1>План доставки товаров по магазинам</h1>\n");
        h.append("<div class=\"muted\">Сформирован ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")))
                .append(" многоагентной системой (JADE): этап 1 — первоначальный план, этап 2 — улучшение</div>\n");

        // ---- сводка
        h.append("<div class=\"cards\">\n");
        card(h, "Заявок доставлено", delivered + " из " + orders.size());
        card(h, "Затраты", rub(cost2));
        card(h, "Экономия этапа 2", rub(cost1 - cost2));
        card(h, "Рейсов", trips1 + " → " + trips2);
        card(h, "Пробег", String.format(RU, "%.1f км", km2));
        card(h, "Машин задействовано", String.valueOf(finalPlan.values().stream().filter(l -> !l.isEmpty()).count()));
        h.append("</div>\n");
        boolean ok = summary.controlCheck().contains("нет");
        h.append("<p class=\"").append(ok ? "ok" : "bad").append("\"><b>")
                .append(esc(summary.controlCheck())).append("</b></p>\n");
        h.append(String.format(RU,
                "<p class=\"muted\">Этап 1: раундов — %d, затраты %s; этап 2: раундов улучшения — %d, "
                        + "передано заявок между машинами — %d, затраты %s</p>%n",
                summary.stage1Rounds(), rub(cost1), summary.improveRounds(), summary.transfers(), rub(cost2)));

        // ---- итоговый план
        h.append("<h2>Итоговый план по складам и машинам</h2>\n");
        appendPlan(h, finalPlan, summary, true);

        // ---- по заявкам
        h.append("<h2>Доставка по заявкам</h2>\n<table><tr><th>Заявка</th><th>Магазин</th><th>Товары</th>"
                + "<th>Вес</th><th>Окно приёмки</th><th>Склад / машина</th><th>Приезд</th><th>Разгрузка до</th>"
                + "<th>В окне</th></tr>\n");
        for (Order o : orders) {
            String items = o.items().stream()
                    .map(i -> i.product() + " " + kg(i.weightKg()))
                    .collect(Collectors.joining(", "));
            h.append("<tr><td>").append(esc(o.id())).append("</td><td>").append(esc(o.store()))
                    .append("</td><td>").append(esc(items)).append("</td><td class=\"num\">")
                    .append(kg(o.totalWeightKg())).append("</td><td>")
                    .append(o.windowFrom().format(HM)).append("–").append(o.windowTo().format(HM)).append("</td>");
            Stop s = stopOf.get(o.id());
            if (s == null) {
                h.append("<td colspan=\"4\" class=\"bad\">не назначена: ни одна машина не смогла "
                        + "доставить в окно приёмки</td></tr>\n");
                continue;
            }
            Trip t = tripOf.get(o.id());
            boolean inWindow = !s.arrival().isBefore(o.windowFrom()) && !s.unloadEnd().isAfter(o.windowTo());
            h.append("<td>").append(esc(t.truck().warehouse())).append(" / ").append(esc(t.truck().name()))
                    .append("</td><td>").append(s.arrival().format(HM)).append("</td><td>")
                    .append(s.unloadEnd().format(HM)).append("</td><td class=\"")
                    .append(inWindow ? "ok\">да" : "bad\">нет").append("</td></tr>\n");
        }
        h.append("</table>\n");

        // ---- первоначальный план
        h.append("<h2>Для сравнения: первоначальный план (после этапа 1)</h2>\n");
        h.append(String.format(RU, "<p class=\"muted\">Рейсов: %d, затраты: %s — на этапе 2 машины передавали друг "
                + "другу заявки, если другая машина могла довезти их попутно дешевле.</p>%n", trips1, rub(cost1)));
        appendPlan(h, stage1, summary, false);

        h.append("<p class=\"muted\">Интервал рейса = погрузка на складе → путь → разгрузка в магазинах → "
                + "возврат на склад. Стоимость = пробег × тариф машины.</p>\n</body></html>\n");

        Files.writeString(file, h.toString(), StandardCharsets.UTF_8);
        return file.toAbsolutePath();
    }

    /** План: склад → машины; для каждой машины — шкала времени и таблица рейсов. */
    private static void appendPlan(StringBuilder h, Map<String, List<Trip>> plan, Summary summary,
                                   boolean withTimeline) {
        Map<String, Map<String, List<Trip>>> byWarehouse = new TreeMap<>();
        for (Map.Entry<String, List<Trip>> e : plan.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String wh = e.getValue().get(0).truck().warehouse();
            byWarehouse.computeIfAbsent(wh, k -> new TreeMap<>()).put(e.getKey(), sorted(e.getValue()));
        }
        for (Map.Entry<String, Map<String, List<Trip>>> w : byWarehouse.entrySet()) {
            h.append("<h3>").append(esc(w.getKey())).append("</h3>\n");

            if (withTimeline) {
                h.append(scale(summary.dayFrom(), summary.dayTo()));
                for (Map.Entry<String, List<Trip>> e : w.getValue().entrySet()) {
                    h.append("<div class=\"tl-row\"><div class=\"tl-name\">").append(esc(e.getKey()))
                            .append("</div><div class=\"tl-wrap\"><div class=\"timeline\">");
                    for (Trip t : e.getValue()) {
                        double left = pct(summary.dayFrom(), summary.dayTo(), t.start());
                        double width = pct(summary.dayFrom(), summary.dayTo(), t.end()) - left;
                        String label = t.stops().stream().map(s -> s.order().id()).collect(Collectors.joining(", "));
                        h.append(String.format(Locale.ROOT,
                                "<div class=\"bar\" style=\"left:%.2f%%;width:%.2f%%\" title=\"%s–%s: %s\">%s</div>",
                                left, Math.max(width, 0.5), t.start().format(HM), t.end().format(HM),
                                esc(label), esc(label)));
                    }
                    h.append("</div></div></div>\n");
                }
            }

            h.append("<table><tr><th>Машина</th><th>Рейс (погрузка — возврат)</th><th>Маршрут: магазин, приезд — "
                    + "конец разгрузки</th><th>Груз</th><th>Пробег</th><th>Стоимость</th></tr>\n");
            for (Map.Entry<String, List<Trip>> e : w.getValue().entrySet()) {
                for (Trip t : e.getValue()) {
                    StringBuilder route = new StringBuilder(esc(w.getKey()));
                    for (Stop s : t.stops()) {
                        route.append(" → <b>").append(esc(s.store().name())).append("</b> (")
                                .append(esc(s.order().id())).append(", ").append(s.arrival().format(HM))
                                .append("—").append(s.unloadEnd().format(HM)).append(")");
                    }
                    route.append(" → ").append(esc(w.getKey()));
                    double loadPct = t.loadKg() / t.truck().capacityKg() * 100;
                    h.append("<tr><td>").append(esc(e.getKey())).append("</td><td>")
                            .append(t.start().format(HM)).append("–").append(t.end().format(HM))
                            .append(String.format(RU, " <span class=\"muted\">(%d мин)</span>",
                                    Duration.between(t.start(), t.end()).toMinutes()))
                            .append("</td><td>").append(route).append("</td><td class=\"num\">")
                            .append(kg(t.loadKg())).append(String.format(RU, " (%.0f%%)", loadPct))
                            .append("</td><td class=\"num\">").append(String.format(RU, "%.1f км", t.distanceKm()))
                            .append("</td><td class=\"num\">").append(rub(t.cost())).append("</td></tr>\n");
                }
            }
            h.append("</table>\n");
        }
    }

    // ------------------------------------------------------------------ служебное

    private static String scale(LocalTime from, LocalTime to) {
        StringBuilder s = new StringBuilder("<div class=\"tl-row\"><div class=\"tl-name\"></div>"
                + "<div class=\"tl-wrap\"><div class=\"scale\">");
        for (LocalTime t = from; !t.isAfter(to); t = t.plusHours(2)) {
            s.append(String.format(Locale.ROOT, "<span style=\"left:%.2f%%\">%s</span>",
                    pct(from, to, t), t.format(HM)));
            if (t.plusHours(2).isBefore(t)) break;   // переход через полночь
        }
        return s.append("</div></div></div>\n").toString();
    }

    private static double pct(LocalTime from, LocalTime to, LocalTime t) {
        double total = Duration.between(from, to).toMinutes();
        double v = Duration.between(from, t).toMinutes() / total * 100;
        return Math.max(0, Math.min(100, v));
    }

    private static List<Trip> sorted(List<Trip> trips) {
        List<Trip> l = new ArrayList<>(trips);
        l.sort(Comparator.comparing(Trip::start));
        return l;
    }

    private static double totalCost(Map<String, List<Trip>> plan) {
        return plan.values().stream().flatMap(List::stream).mapToDouble(Trip::cost).sum();
    }

    private static int tripCount(Map<String, List<Trip>> plan) {
        return plan.values().stream().mapToInt(List::size).sum();
    }

    private static void card(StringBuilder h, String title, String value) {
        h.append("<div class=\"card\"><span class=\"muted\">").append(esc(title)).append("</span><b>")
                .append(esc(value)).append("</b></div>\n");
    }

    private static String rub(double v) {
        return String.format(RU, "%,.0f руб.", v);
    }

    private static String kg(double v) {
        return String.format(RU, "%.0f кг", v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
