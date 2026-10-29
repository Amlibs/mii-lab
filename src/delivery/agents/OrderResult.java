package delivery.agents;

import delivery.model.Trip;

import java.io.Serializable;

/**
 * Итог по заявке, который магазин сообщает складу.
 *
 * @param orderId номер заявки
 * @param store   имя магазина
 * @param trip    забронированный рейс; null — заявку назначить не удалось
 */
public record OrderResult(String orderId, String store, Trip trip) implements Serializable {
}
