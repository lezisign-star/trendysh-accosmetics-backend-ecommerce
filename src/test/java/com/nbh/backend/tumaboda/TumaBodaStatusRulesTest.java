package com.nbh.backend.tumaboda;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which reports from TumaBoda are news and which are stale, out-of-order noise. */
class TumaBodaStatusRulesTest {

    private static boolean stale(String recorded, String incoming) {
        return TumaBodaStatusService.isOlderThanRecorded(recorded, incoming);
    }

    @Test
    void theFirstReportIsAlwaysNews() {
        assertThat(stale(null, "pending")).isFalse();
        assertThat(stale(null, "failed")).isFalse();
    }

    @Test
    void aDeliveryMovesForwardAndALateEarlierReportIsIgnored() {
        assertThat(stale("pending", "assigned")).isFalse();
        assertThat(stale("assigned", "picked_up")).isFalse();
        assertThat(stale("picked_up", "in_transit")).isFalse();
        assertThat(stale("in_transit", "delivered")).isFalse();

        assertThat(stale("in_transit", "assigned")).as("late 'assigned'").isTrue();
        assertThat(stale("in_transit", "picked_up")).isTrue();
        assertThat(stale("picked_up", "pending")).isTrue();
    }

    @Test
    void theSameReportAgainIsHarmlessAndAllowedThrough() {
        assertThat(stale("in_transit", "in_transit")).isFalse();
    }

    @Test
    void aStatusWeDoNotKnowIsRecordedButNeverBlocksAKnownLaterOne() {
        assertThat(stale("assigned", "arrived_at_dropoff")).isFalse();
        assertThat(stale("arrived_at_dropoff", "in_transit")).isFalse();
    }

    @Test
    void aFailedCancelledOrReturnedReportCanFollowAnyProgress() {
        for (String recorded : new String[]{"pending", "assigned", "picked_up", "in_transit"}) {
            for (String end : new String[]{"failed", "cancelled", "canceled", "returned"}) {
                assertThat(stale(recorded, end)).as(recorded + " -> " + end).isFalse();
            }
        }
    }

    @Test
    void afterADeliveryDiesLateProgressReportsDoNotBringItBack() {
        for (String dead : new String[]{"failed", "cancelled", "returned"}) {
            assertThat(stale(dead, "in_transit")).as(dead + " then in_transit").isTrue();
            assertThat(stale(dead, "assigned")).isTrue();
        }
    }

    @Test
    void anotherEndStateAfterADeadOneStillCounts() {
        assertThat(stale("failed", "cancelled")).isFalse();
        assertThat(stale("failed", "returned")).isFalse();
    }

    @Test
    void deliveredAfterFailedMeansTheParcelDidArrive() {
        assertThat(stale("failed", "delivered")).isFalse();
        assertThat(stale("cancelled", "delivered")).isFalse();
    }

    @Test
    void deliveredIsFinal() {
        assertThat(stale("delivered", "delivered")).as("a repeat").isFalse();
        assertThat(stale("delivered", "cancelled")).isTrue();
        assertThat(stale("delivered", "failed")).isTrue();
        assertThat(stale("delivered", "in_transit")).isTrue();
    }

    @Test
    void statusesAreMatchedIgnoringCase() {
        assertThat(stale("IN_TRANSIT", "Assigned")).isTrue();
        assertThat(stale("Delivered", "CANCELLED")).isTrue();
        assertThat(stale("FAILED", "In_Transit")).isTrue();
    }
}
