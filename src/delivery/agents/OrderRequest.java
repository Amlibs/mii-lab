package delivery.agents;

import delivery.model.Order;
import delivery.model.Store;

import java.io.Serializable;

/**
 * Заявка, которую склад-координатор рассылает машинам: сама заявка + данные магазина.
 * Данные склада не передаются: каждая машина считает рейс от СВОЕГО склада.
 */
public record OrderRequest(Order order, Store store) implements Serializable {
}
