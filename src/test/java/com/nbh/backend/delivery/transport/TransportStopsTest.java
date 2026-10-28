package com.nbh.backend.delivery.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A typed drop-off location is tidied into Title Case before others see it (owner, 2026-10-28) and matched by key. */
class TransportStopsTest {

    @Test
    void aTypedLocationIsTidiedIntoTitleCase() {
        assertThat(TransportStops.clean("  kencom   stage , moi avenue ")).isEqualTo("Kencom Stage, Moi Avenue");
        assertThat(TransportStops.clean("RONGAI total petrol station")).isEqualTo("Rongai Total Petrol Station");
        assertThat(TransportStops.clean("opposite the cbd gpo")).isEqualTo("Opposite the CBD GPO");
        assertThat(TransportStops.clean("githurai 45 kwa-mama")).isEqualTo("Githurai 45 Kwa-Mama");
    }

    @Test
    void aPhoneNumberIsNeverKeptForTheNextShopperToSee() {
        assertThat(TransportStops.clean("kitengela stage call 0712 345 678")).isEqualTo("Kitengela Stage Call");
        assertThat(TransportStops.clean("+254712345678")).isNull();
        assertThat(TransportStops.clean("   ")).isNull();
    }

    @Test
    void similarSpellingsOfOnePlaceShareAKey() {
        assertThat(TransportStops.key("Kencom Stage")).isEqualTo(TransportStops.key("kencom"));
        assertThat(TransportStops.key("The Kencom bus stop")).isEqualTo("kencom");
        assertThat(TransportStops.key("Juja")).isNotEqualTo(TransportStops.key("Juja Farm"));
    }
}
