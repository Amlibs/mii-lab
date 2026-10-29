package delivery.model;

import java.io.Serializable;

/**
 * Магазин.
 *
 * @param name             уникальное имя (станет именем агента)
 * @param x                координата X, км
 * @param y                координата Y, км
 * @param unloadingMinutes время разгрузки у магазина, мин
 */
public record Store(String name, double x, double y,
                    int unloadingMinutes) implements Serializable {
}
