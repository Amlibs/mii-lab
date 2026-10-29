package delivery.model;

import java.io.Serializable;
import java.time.LocalTime;

/**
 * Склад: точка, откуда выезжают и куда возвращаются автомобили.
 *
 * @param name           название склада
 * @param x              координата X, км
 * @param y              координата Y, км
 * @param workFrom       начало работы склада (раньше погрузка не начинается)
 * @param workTo         конец работы склада (к этому времени авто должны вернуться)
 * @param loadingMinutes время погрузки одного рейса, мин
 */
public record Warehouse(String name, double x, double y,
                        LocalTime workFrom, LocalTime workTo,
                        int loadingMinutes) implements Serializable {
}
