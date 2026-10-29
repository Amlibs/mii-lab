package delivery.model;

import java.io.Serializable;

/**
 * Строка заявки: товар и его общий вес.
 *
 * @param product  название товара
 * @param weightKg общий вес этого товара в заявке, кг
 */
public record OrderItem(String product, double weightKg) implements Serializable {
}
