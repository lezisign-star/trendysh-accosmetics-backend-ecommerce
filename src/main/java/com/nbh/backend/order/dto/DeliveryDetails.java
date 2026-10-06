package com.nbh.backend.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.DeliveryTimeWindow;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional extras a customer can give about a delivery: where exactly, how to find it, who it is handed to and
 * when (2026-10-07, owner's request: "more delivery location details ... even if they will be optional"). Every field
 * may be null. They travel on the checkout request, are held on the order as the flat {@code delivery_*} columns of
 * V55, and are shown to staff in the order panel. Who to ring at the door is not here: that is the existing
 * {@code deliveryContactPhone} (V47).
 *
 * <p>Which fields apply depends on how the order travels ({@link #cleanedFor}): a rider's doorstep delivery (BODA)
 * takes the street, building and floor or door; a drop at a stage (MATATU) takes none of those; a courier or sacco
 * parcel (COURIER) takes the courier's office instead, and has no receiver name (the collector is already a required
 * part of that form) and no time window (the courier decides). Anything else is dropped rather than stored, so staff
 * only ever see what the shopper was actually asked for.
 */
public record DeliveryDetails(
        @Size(max = 120, message = "Estate or area must be 120 characters or fewer") String estate,
        @Size(max = 120, message = "Street or road must be 120 characters or fewer") String street,
        @Size(max = 120, message = "Building or house name must be 120 characters or fewer") String building,
        @Size(max = 60, message = "Floor, door or house number must be 60 characters or fewer") String unit,
        @Size(max = 120, message = "Courier office or branch must be 120 characters or fewer") String courierOffice,
        @Size(max = 400, message = "Directions must be 400 characters or fewer") String directions,
        @Size(max = 400, message = "Delivery instructions must be 400 characters or fewer") String instructions,
        @Size(max = 100, message = "The receiver's name must be 100 characters or fewer") String receiverName,
        DeliveryTimeWindow timeWindow
) {

    /** TumaBoda's {@code location} is free text; this is the least a text column of that kind can be assumed to take. */
    static final int MAX_LOCATION = 255;

    /** Not a field of the JSON: without this, Jackson would add an {@code "empty"} property to every response. */
    @JsonIgnore
    public boolean isEmpty() {
        return estate == null && street == null && building == null && unit == null && courierOffice == null
                && directions == null && instructions == null && receiverName == null && timeWindow == null;
    }

    /**
     * What is worth keeping from what the shopper sent, for the way the order travels: text trimmed and flattened,
     * control characters removed, blank fields gone, and the fields that method does not ask for dropped. Null when
     * nothing is left (or the method is not a delivery one), so an order with no extras holds no extras.
     */
    public DeliveryDetails cleanedFor(String deliveryMethod) {
        boolean boda = "BODA".equals(deliveryMethod);
        boolean matatu = "MATATU".equals(deliveryMethod);
        boolean courier = "COURIER".equals(deliveryMethod);
        if (!boda && !matatu && !courier) {
            return null;
        }
        DeliveryDetails cleaned = new DeliveryDetails(
                oneLine(estate),
                boda ? oneLine(street) : null,
                boda ? oneLine(building) : null,
                boda ? oneLine(unit) : null,
                courier ? oneLine(courierOffice) : null,
                lines(directions),
                lines(instructions),
                courier ? null : oneLine(receiverName),
                courier ? null : timeWindow);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** What the order holds, or null when the customer gave nothing. */
    public static DeliveryDetails of(CustomerOrder o) {
        DeliveryDetails held = new DeliveryDetails(o.getDeliveryEstate(), o.getDeliveryStreet(),
                o.getDeliveryBuilding(), o.getDeliveryUnit(), o.getDeliveryCourierOffice(), o.getDeliveryDirections(),
                o.getDeliveryInstructions(), o.getDeliveryReceiverName(), o.getDeliveryTimeWindow());
        return held.isEmpty() ? null : held;
    }

    /** Writes {@code details} onto the order; null clears them. */
    public static void store(DeliveryDetails details, CustomerOrder o) {
        o.setDeliveryEstate(details == null ? null : details.estate());
        o.setDeliveryStreet(details == null ? null : details.street());
        o.setDeliveryBuilding(details == null ? null : details.building());
        o.setDeliveryUnit(details == null ? null : details.unit());
        o.setDeliveryCourierOffice(details == null ? null : details.courierOffice());
        o.setDeliveryDirections(details == null ? null : details.directions());
        o.setDeliveryInstructions(details == null ? null : details.instructions());
        o.setDeliveryReceiverName(details == null ? null : details.receiverName());
        o.setDeliveryTimeWindow(details == null ? null : details.timeWindow());
    }

    /**
     * The delivery address as a TumaBoda rider should read it: the address the shopper picked, then what they added
     * to help find the door (building, floor or door, street, estate, directions, a note). TumaBoda's
     * {@code location} is one line of free text, so line breaks become separators and the whole is cut at
     * {@value #MAX_LOCATION} characters rather than risk a refused booking over a long note.
     */
    public static String locationFor(String address, DeliveryDetails details) {
        StringBuilder text = new StringBuilder(address == null ? "" : address.trim());
        if (details != null) {
            List<String> where = new ArrayList<>();
            for (String part : new String[] {details.building(), details.unit(), details.street(), details.estate()}) {
                if (part != null) {
                    where.add(part);
                }
            }
            append(text, String.join(", ", where));
            append(text, flat(details.directions()));
            String note = flat(details.instructions());
            append(text, note == null ? null : "Note: " + note);
        }
        String line = text.toString();
        if (line.length() <= MAX_LOCATION) {
            return line;
        }
        int cut = MAX_LOCATION - 1;
        if (Character.isHighSurrogate(line.charAt(cut - 1))) {
            cut--; // never leave half of an emoji behind
        }
        return line.substring(0, cut).stripTrailing() + "…";
    }

    private static void append(StringBuilder text, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(" — ");
        }
        text.append(part);
    }

    private static String flat(String lines) {
        return lines == null ? null : lines.replace("\n", ", ");
    }

    /** One line: control characters and runs of whitespace (line breaks included) become a single space. */
    private static String oneLine(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\p{Cc}\\s]+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    /** Free text that may run over several lines: each line cleaned as above, blank lines dropped. */
    private static String lines(String raw) {
        if (raw == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String cleaned = oneLine(line);
            if (cleaned != null) {
                kept.add(cleaned);
            }
        }
        return kept.isEmpty() ? null : String.join("\n", kept);
    }
}
