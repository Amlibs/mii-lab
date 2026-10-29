package delivery.model;

import java.io.Serializable;

/**
 * Автомобиль доставки.
 *
 * @param name       уникальное имя (станет именем агента)
 * @param warehouse  имя склада, к которому приписана машина: отсюда она выезжает и сюда возвращается
 * @param capacityKg грузоподъёмность, кг
 * @param speedKmh   средняя скорость, км/ч
 * @param costPerKm  стоимость 1 км пробега, руб.
 */
public record Truck(String name, String warehouse, double capacityKg, double speedKmh,
                    double costPerKm) implements Serializable {
}
