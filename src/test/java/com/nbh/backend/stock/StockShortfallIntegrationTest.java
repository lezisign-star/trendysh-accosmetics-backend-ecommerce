package com.nbh.backend.stock;

import com.nbh.backend.stock.entity.StockShortfallEvent;
import com.nbh.backend.stock.service.StockShortfallService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "A customer could not order" (owner, 2026-10-28) against real Postgres: a refusal is written down once per shopper and
 * option, stays on the banner until a person marks it handled, and "mark all handled" leaves a newer one alone.
 */
@SpringBootTest
@ActiveProfiles("test")
class StockShortfallIntegrationTest {

    @Autowired private StockShortfallService service;

    private boolean listed(UUID variantId) {
        return service.unhandled().events().stream().anyMatch(e -> e.variantId().equals(variantId));
    }

    @Test
    void theSameShopperTryingAgainIsOneEventAndHandledTakesItOffTheBanner() {
        UUID variant = UUID.randomUUID();
        String session = "sess-" + UUID.randomUUID();
        service.record(variant, "Gel polish (Red)", new BigDecimal("3"), BigDecimal.ONE,
                StockShortfallEvent.Source.CART, session, null, null, null);
        service.record(variant, "Gel polish (Red)", new BigDecimal("3"), BigDecimal.ONE,
                StockShortfallEvent.Source.CART, session, null, null, null);
        service.record(variant, "Gel polish (Red)", BigDecimal.ONE, BigDecimal.ZERO,
                StockShortfallEvent.Source.CHECKOUT, "other-" + UUID.randomUUID(), "Jane", "254700000000", "j@example.com");

        var mine = service.unhandled().events().stream().filter(e -> e.variantId().equals(variant)).toList();
        assertThat(mine).hasSize(2);
        var atCheckout = mine.stream().filter(e -> e.source().equals("CHECKOUT")).findFirst().orElseThrow();
        assertThat(atCheckout.contactPhone()).isEqualTo("254700000000");

        mine.forEach(e -> service.handle(e.id(), "staff@example.com"));
        assertThat(listed(variant)).isFalse();
        assertThat(service.refusedLately(30).stream().filter(o -> o.variantId().equals(variant)).findFirst()
                .orElseThrow().times()).as("still on the Restock screen").isEqualTo(2);
    }

    @Test
    void markAllHandledStopsAtTheMomentTheListWasRead() throws Exception {
        UUID before = UUID.randomUUID();
        service.record(before, "Lash glue", BigDecimal.ONE, BigDecimal.ZERO,
                StockShortfallEvent.Source.CART, null, null, null, null);
        var read = service.unhandled();
        Thread.sleep(20);
        UUID after = UUID.randomUUID();
        service.record(after, "Lash glue", BigDecimal.ONE, BigDecimal.ZERO,
                StockShortfallEvent.Source.CART, null, null, null, null);

        service.handleAll(read.upTo(), "staff@example.com");

        assertThat(listed(before)).isFalse();
        assertThat(listed(after)).isTrue();
    }
}
