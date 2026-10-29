package delivery.model;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.List;

/**
 * Рейс: склад → магазины (по порядку остановок) → склад.
 *
 * @param truck      автомобиль
 * @param stops      остановки по порядку объезда (1 — обычный рейс, 2 — попутный)
 * @param start      начало погрузки на складе — начало интервала занятости
 * @param end        возврат на склад — конец интервала занятости
 * @param distanceKm полный пробег рейса, км
 * @param cost       стоимость рейса, руб.
 */
public record Trip(Truck truck, List<Stop> stops,
                   LocalTime start, LocalTime end,
                   double distanceKm, double cost) implements Serializable {

    /** Пересекаются ли интервалы занятости двух рейсов (касание — не пересечение). */
    public boolean overlaps(Trip other) {
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }

    /** Общий вес груза в рейсе, кг. */
    public double loadKg() {
        double sum = 0;
        for (Stop s : stops) {
            sum += s.order().totalWeightKg();
        }
        return sum;
    }
}
