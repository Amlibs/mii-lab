package delivery.model;

import java.io.Serializable;
import java.time.LocalTime;

/**
 * Остановка рейса: один магазин и одна заявка.
 * В обычном рейсе одна остановка, в попутном — две.
 *
 * @param store     магазин
 * @param order     заявка, которую везём в этот магазин
 * @param arrival   приезд в магазин (= начало разгрузки)
 * @param unloadEnd конец разгрузки
 */
public record Stop(Store store, Order order,
                   LocalTime arrival, LocalTime unloadEnd) implements Serializable {
}
