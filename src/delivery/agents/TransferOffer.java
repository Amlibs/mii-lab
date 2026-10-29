package delivery.agents;

import delivery.model.Trip;

import java.io.Serializable;

/**
 * Этап 2: ответ машины, готовой забрать заявку попутно.
 *
 * @param newTrip   её рейс после добавления заявки
 * @param extraCost на сколько подорожает её рейс, руб.
 */
public record TransferOffer(Trip newTrip, double extraCost) implements Serializable {
}
