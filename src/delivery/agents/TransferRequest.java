package delivery.agents;

import delivery.model.Order;
import delivery.model.Store;

import java.io.Serializable;

/**
 * Этап 2: машина предлагает другим машинам забрать её заявку попутно.
 * Принимающая машина считает рейс от СВОЕГО склада.
 *
 * @param order     заявка
 * @param store     магазин заявки
 * @param giverCost сколько стоит отдающей машине её отдельный рейс с этой заявкой, руб. —
 *                  передача выгодна, только если добавка у принимающей меньше
 */
public record TransferRequest(Order order, Store store, double giverCost) implements Serializable {
}
