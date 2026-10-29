package delivery.model;

import java.io.Serializable;
import java.time.LocalTime;
import java.util.List;

/**
 * Заявка магазина из пула заявок.
 *
 * @param id         номер заявки
 * @param store      имя магазина (совпадает с name в stores.json)
 * @param windowFrom начало окна приёмки (работа грузчиков)
 * @param windowTo   конец окна приёмки; разгрузка должна закончиться не позже
 * @param items      товары заявки
 */
public record Order(String id, String store, LocalTime windowFrom, LocalTime windowTo,
                    List<OrderItem> items) implements Serializable {

    /** Общий вес заявки, кг. */
    public double totalWeightKg() {
        return items.stream().mapToDouble(OrderItem::weightKg).sum();
    }
}
