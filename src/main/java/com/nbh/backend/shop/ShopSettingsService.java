package com.nbh.backend.shop;

import com.nbh.backend.common.event.DataChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shop's one settings row: how it takes orders ({@link OrderingMode}). Changing it tells every open storefront (the
 * same live hint a catalogue change sends), so a shopper's page follows the switch without a reload.
 */
@Service
public class ShopSettingsService {

    private final ShopSettingsRepository repository;
    private final ApplicationEventPublisher events;

    public ShopSettingsService(ShopSettingsRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /** The one settings row; created on the fly if a database somehow has none. */
    @Transactional
    public ShopSettings get() {
        return repository.findFirstByOrderByCreatedAtAsc().orElseGet(() -> repository.save(new ShopSettings()));
    }

    /** Saying the mode it already has changes nothing and tells nobody. */
    @Transactional
    public ShopSettings setOrderingMode(OrderingMode mode, String changedBy) {
        ShopSettings settings = get();
        if (settings.getOrderingMode() == mode) {
            return settings;
        }
        settings.setOrderingMode(mode);
        settings.setChangedBy(changedBy);
        ShopSettings saved = repository.save(settings);
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
        return saved;
    }
}
