package com.nbh.backend.live;

import com.nbh.backend.catalog.entity.Brand;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.stock.entity.StockBalance;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * One place that turns "a row of a watched entity was written" into a {@link DataChangedEvent}, so every way
 * of changing products, categories, promotions or stock (admin screens, checkout, imports, schedulers) pushes
 * a live update without each service having to remember to. The event is delivered after commit and
 * coalesced by {@link LiveUpdateListener}, so a bulk change is one hint, not thousands.
 */
@Component
public class EntityChangeListener {

    private final ApplicationEventPublisher events;

    public EntityChangeListener(ApplicationEventPublisher events) {
        this.events = events;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(Object entity) {
        DataChangedEvent.Topic topic = topicOf(entity);
        if (topic != null) {
            events.publishEvent(new DataChangedEvent(topic));
        }
    }

    private static DataChangedEvent.Topic topicOf(Object entity) {
        if (entity instanceof Product || entity instanceof ProductVariant || entity instanceof ProductImage
                || entity instanceof Brand) {
            return DataChangedEvent.Topic.PRODUCTS;
        }
        if (entity instanceof Category || entity instanceof PromoCampaign) {
            return DataChangedEvent.Topic.CATALOG;
        }
        if (entity instanceof StockBalance) {
            return DataChangedEvent.Topic.STOCK;
        }
        return null;
    }
}
