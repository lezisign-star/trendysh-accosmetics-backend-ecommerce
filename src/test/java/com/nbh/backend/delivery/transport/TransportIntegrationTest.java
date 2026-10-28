package com.nbh.backend.delivery.transport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Metros against real Postgres (V82): the seeded operators are there, a location a shopper types is kept for the next
 * shopper of that operator (tidied), a similar spelling is the same place with its fare, and staff set fares.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransportIntegrationTest {

    @Autowired private TransportService service;

    private UUID newOperator() {
        return service.saveOperator(null, new TransportService.SaveOperator(
                "QA Sacco " + UUID.randomUUID().toString().substring(0, 6), "Somewhere", true, 5)).id();
    }

    @Test
    void theResearchedOperatorsAreSeededWithSuperMetroAndNicco() {
        var names = service.operatorsForShoppers().stream().map(TransportService.OperatorDto::name).toList();
        assertThat(names).contains("Super Metro", "Nicco Movers", "Embassava", "Forward Travellers");
    }

    @Test
    void aTypedLocationIsKeptTidiedAndASimilarSpellingFindsItWithTheFareStaffSet() {
        UUID op = newOperator();
        var first = service.resolveForOrder(op, "  kencom stage ");
        assertThat(first.stopName()).isEqualTo("Kencom Stage");
        assertThat(first.fareMin()).isNull();
        assertThat(service.suggest(op, "kenc")).extracting(TransportService.StopDto::name).containsExactly("Kencom Stage");

        var stop = service.stopsForStaff(op).get(0);
        service.saveStop(op, stop.id(), new TransportService.SaveStop("Kencom Stage", new BigDecimal("150"),
                new BigDecimal("200"), false));

        var again = service.resolveForOrder(op, "KENCOM");
        assertThat(again.stopName()).isEqualTo("Kencom Stage");
        assertThat(again.fareMin()).isEqualByComparingTo("150");
        assertThat(again.fareMax()).isEqualByComparingTo("200");
        assertThat(service.stopsForStaff(op)).hasSize(1).first()
                .satisfies(s -> assertThat(s.uses()).isEqualTo(2));
    }

    @Test
    void aHiddenLocationIsNotSuggestedAndAnUnknownOperatorLeavesTheOrderAsTyped() {
        UUID op = newOperator();
        service.resolveForOrder(op, "my gate opposite the kiosk");
        var stop = service.stopsForStaff(op).get(0);
        service.saveStop(op, stop.id(), new TransportService.SaveStop(stop.name(), null, null, true));
        assertThat(service.suggest(op, "")).isEmpty();
        assertThat(service.resolveForOrder(UUID.randomUUID(), "Kencom")).isNull();
    }

    @Test
    void twoOperatorsCannotShareAName() {
        UUID op = newOperator();
        String name = service.operatorsForStaff().stream().filter(o -> o.id().equals(op)).findFirst().orElseThrow().name();
        assertThatThrownBy(() -> service.saveOperator(null, new TransportService.SaveOperator(name.toLowerCase(), null, true, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
