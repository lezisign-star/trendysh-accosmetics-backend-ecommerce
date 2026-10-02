package com.nbh.backend.common.event;

/**
 * Something in a shared collection changed (products, stock, delivery places...). Published by the service
 * that changed it, delivered after commit as a live "look again" hint — never carries the data itself.
 */
public record DataChangedEvent(Topic topic) {

    public enum Topic {
        DELIVERY_PLACES, DELIVERY_SUGGESTIONS, PRODUCTS, CATALOG, STOCK, IMAGE_CLEANUP;

        /** Whether storefront visitors, not just staff, should be told. */
        public boolean isPublic() {
            return this != DELIVERY_SUGGESTIONS && this != IMAGE_CLEANUP;
        }
    }
}
