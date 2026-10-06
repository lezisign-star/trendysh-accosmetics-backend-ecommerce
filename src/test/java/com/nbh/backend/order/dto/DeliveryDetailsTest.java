package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.DeliveryTimeWindow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optional extras a customer can give about a delivery (2026-10-07). What is kept depends on how the order
 * travels, text is tidied before it is stored, and the rider is given the address followed by the way to the door.
 */
class DeliveryDetailsTest {

    private static DeliveryDetails everything() {
        return new DeliveryDetails("Kilimani", "Argwings Kodhek Rd", "Yaya Towers", "4th floor, door 12",
                "Easy Coach Nakuru", "Opposite the pharmacy", "Call at the gate", "Njeri Receptionist",
                DeliveryTimeWindow.MORNING);
    }

    @Test
    void aRidersDoorstepDeliveryKeepsEverythingExceptTheCouriersOffice() {
        assertThat(everything().cleanedFor("BODA")).isEqualTo(new DeliveryDetails("Kilimani", "Argwings Kodhek Rd",
                "Yaya Towers", "4th floor, door 12", null, "Opposite the pharmacy", "Call at the gate",
                "Njeri Receptionist", DeliveryTimeWindow.MORNING));
    }

    @Test
    void aDropAtAStageKeepsTheAreaDirectionsAndWhoCollectsButNoBuildingOrFloor() {
        assertThat(everything().cleanedFor("MATATU")).isEqualTo(new DeliveryDetails("Kilimani", null, null, null, null,
                "Opposite the pharmacy", "Call at the gate", "Njeri Receptionist", DeliveryTimeWindow.MORNING));
    }

    @Test
    void aCourierParcelKeepsTheOfficeButNoReceiverNameOrTimeOfDay() {
        assertThat(everything().cleanedFor("COURIER")).isEqualTo(new DeliveryDetails("Kilimani", null, null, null,
                "Easy Coach Nakuru", "Opposite the pharmacy", "Call at the gate", null, null));
    }

    @Test
    void anythingThatIsNotADeliveryMethodKeepsNothing() {
        assertThat(everything().cleanedFor(null)).isNull();
        assertThat(everything().cleanedFor("PICKUP")).isNull();
        assertThat(everything().cleanedFor("boda")).as("the methods are spelled in capitals everywhere else").isNull();
    }

    @Test
    void messyTextIsTidiedAndAnEmptyResultIsNullNotAnObjectOfNulls() {
        DeliveryDetails messy = new DeliveryDetails("  Kilimani \t ", "   ", null, null, null,
                " Blue  gate \r\n\r\n  second\u0007 line ", "\n\n", null, null);

        DeliveryDetails kept = messy.cleanedFor("BODA");

        assertThat(kept.estate()).isEqualTo("Kilimani");
        assertThat(kept.street()).as("blank is nothing").isNull();
        assertThat(kept.directions()).as("lines stay lines, blank ones and stray control characters go")
                .isEqualTo("Blue gate\nsecond line");
        assertThat(kept.instructions()).isNull();

        assertThat(new DeliveryDetails(" ", null, null, null, null, "", " \n ", null, null).cleanedFor("MATATU")).isNull();
        assertThat(new DeliveryDetails(null, null, null, null, null, null, null, null, null).cleanedFor("BODA")).isNull();
    }

    @Test
    void aSingleLineFieldNeverKeepsALineBreak() {
        assertThat(new DeliveryDetails("Kilimani\nNairobi", null, null, null, null, null, null, null, null)
                .cleanedFor("MATATU").estate()).isEqualTo("Kilimani Nairobi");
    }

    @Test
    void theRiderReadsTheAddressThenTheWayToTheDoor() {
        assertThat(DeliveryDetails.locationFor("Kilimani, Wood Avenue", everything().cleanedFor("BODA")))
                .isEqualTo("Kilimani, Wood Avenue — Yaya Towers, 4th floor, door 12, Argwings Kodhek Rd, Kilimani"
                        + " — Opposite the pharmacy — Note: Call at the gate");
    }

    @Test
    void withNothingAddedTheLocationIsJustTheAddress() {
        assertThat(DeliveryDetails.locationFor("  Kilimani, Wood Avenue ", null)).isEqualTo("Kilimani, Wood Avenue");
        assertThat(DeliveryDetails.locationFor(null, null)).isEmpty();
    }

    @Test
    void lineBreaksInANoteBecomeSeparatorsBecauseTheLocationIsOneLine() {
        DeliveryDetails details = new DeliveryDetails(null, null, null, null, null, null, "Gate is blue\nCall me", null, null);
        assertThat(DeliveryDetails.locationFor("Kilimani", details)).isEqualTo("Kilimani — Note: Gate is blue, Call me");
    }

    @Test
    void aLongNoteIsCutShortRatherThanRiskingARefusedBooking() {
        DeliveryDetails details = new DeliveryDetails(null, null, null, null, null, null, "x".repeat(400), null, null);

        String location = DeliveryDetails.locationFor("Kilimani", details);

        assertThat(location).hasSize(DeliveryDetails.MAX_LOCATION).startsWith("Kilimani — Note: xxx").endsWith("x…");
    }

    @Test
    void theCutNeverLeavesHalfOfAnEmojiBehind() {
        DeliveryDetails details = new DeliveryDetails(null, null, null, null, null, null, null, null, null);
        // 253 letters, then emoji: the 254th character is the first half of the first emoji.
        String location = DeliveryDetails.locationFor("a".repeat(253) + "😀😀", details);

        assertThat(location).isEqualTo("a".repeat(253) + "…");
    }
}
