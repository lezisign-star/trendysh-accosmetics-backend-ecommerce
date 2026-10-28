package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.FulfilmentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * {@code dropoffLat}/{@code dropoffLng} are the point the customer picked for a TumaBoda delivery. With
 * them (and the TumaBoda integration on) the order is booked through the API and the quoted fee is added
 * to the total; without them TumaBoda is arranged by phone as before.
 *
 * <p>{@code expectedSubtotal}/{@code expectedDeliveryFee} are what the customer was SHOWN. They are never
 * used to set a price (the server always charges its own): only to notice that the real amount is no
 * longer the one on screen and refuse with 409 {@code PRICE_CHANGED} instead of charging something else.
 * Both optional, so an older client that sends neither behaves as before.
 *
 * <p>{@code deliveryDetails} are the optional extras about a delivery (where exactly, how to find it, who receives it,
 * when). They are kept only for a delivery, and only the ones that suit its {@code deliveryMethod}.
 *
 * <p>{@code termsVersion}/{@code privacyVersion} are the versions of the Terms of use and the Privacy notice the shopper
 * ticked at checkout (the date each page was last changed). Optional, so an older client that sends neither still orders.
 */
public record CheckoutRequest(
        @NotBlank @Size(max = 200) String customerName,
        @NotBlank @Email String guestEmail,
        @NotBlank String guestPhone,
        @NotNull FulfilmentType fulfilmentType,
        String deliveryAddress,
        @Pattern(regexp = "BODA|MATATU|COURIER", message = "deliveryMethod must be BODA, MATATU or COURIER") String deliveryMethod,
        BigDecimal dropoffLat,
        BigDecimal dropoffLng,
        @PositiveOrZero BigDecimal expectedSubtotal,
        @PositiveOrZero BigDecimal expectedDeliveryFee,
        /** Who the rider should call at drop-off, if different from {@code guestPhone}. Optional — null means "same as above". */
        String deliveryContactPhone,
        /** Optional extras about a delivery; null means the customer gave none. */
        @Valid DeliveryDetails deliveryDetails,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "termsVersion must be a date like 2026-10-28") String termsVersion,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "privacyVersion must be a date like 2026-10-28") String privacyVersion,
        /** A Metro delivery: the operator chosen and where it should be dropped (V82). Optional. */
        @Valid Transport transport,
        /** What the checkout keeps for a signed-in shopper's next order, as it sent it; at most 4,000 characters. */
        @Size(max = 4000) String deliveryProfile
) {
    public record Transport(java.util.UUID operatorId, @Size(max = 200) String stop) {}

    /** The request as it was before Metro operators and the saved delivery existed. */
    public CheckoutRequest(String customerName, String guestEmail, String guestPhone, FulfilmentType fulfilmentType,
                           String deliveryAddress, String deliveryMethod, BigDecimal dropoffLat, BigDecimal dropoffLng,
                           BigDecimal expectedSubtotal, BigDecimal expectedDeliveryFee, String deliveryContactPhone,
                           DeliveryDetails deliveryDetails, String termsVersion, String privacyVersion) {
        this(customerName, guestEmail, guestPhone, fulfilmentType, deliveryAddress, deliveryMethod,
                dropoffLat, dropoffLng, expectedSubtotal, expectedDeliveryFee, deliveryContactPhone, deliveryDetails,
                termsVersion, privacyVersion, null, null);
    }

    /** The request as it was before the drop-off point existed: delivery arranged by phone. */
    public CheckoutRequest(String customerName, String guestEmail, String guestPhone, FulfilmentType fulfilmentType,
                           String deliveryAddress, String deliveryMethod) {
        this(customerName, guestEmail, guestPhone, fulfilmentType, deliveryAddress, deliveryMethod, null, null, null, null, null, null, null, null);
    }

    /** The request as it was before the shown-price check existed. */
    public CheckoutRequest(String customerName, String guestEmail, String guestPhone, FulfilmentType fulfilmentType,
                           String deliveryAddress, String deliveryMethod, BigDecimal dropoffLat, BigDecimal dropoffLng) {
        this(customerName, guestEmail, guestPhone, fulfilmentType, deliveryAddress, deliveryMethod,
                dropoffLat, dropoffLng, null, null, null, null, null, null);
    }

    /** The request as it was before the delivery-contact phone existed. */
    public CheckoutRequest(String customerName, String guestEmail, String guestPhone, FulfilmentType fulfilmentType,
                           String deliveryAddress, String deliveryMethod, BigDecimal dropoffLat, BigDecimal dropoffLng,
                           BigDecimal expectedSubtotal, BigDecimal expectedDeliveryFee) {
        this(customerName, guestEmail, guestPhone, fulfilmentType, deliveryAddress, deliveryMethod,
                dropoffLat, dropoffLng, expectedSubtotal, expectedDeliveryFee, null, null, null, null);
    }

    /** The request as it was before the optional delivery details existed. */
    public CheckoutRequest(String customerName, String guestEmail, String guestPhone, FulfilmentType fulfilmentType,
                           String deliveryAddress, String deliveryMethod, BigDecimal dropoffLat, BigDecimal dropoffLng,
                           BigDecimal expectedSubtotal, BigDecimal expectedDeliveryFee, String deliveryContactPhone) {
        this(customerName, guestEmail, guestPhone, fulfilmentType, deliveryAddress, deliveryMethod,
                dropoffLat, dropoffLng, expectedSubtotal, expectedDeliveryFee, deliveryContactPhone, null, null, null);
    }

    /** The request as it was before the Terms of use and Privacy notice boxes existed. */
    public CheckoutRequest(String customerName, String guestEmail, String guestPhone, FulfilmentType fulfilmentType,
                           String deliveryAddress, String deliveryMethod, BigDecimal dropoffLat, BigDecimal dropoffLng,
                           BigDecimal expectedSubtotal, BigDecimal expectedDeliveryFee, String deliveryContactPhone,
                           DeliveryDetails deliveryDetails) {
        this(customerName, guestEmail, guestPhone, fulfilmentType, deliveryAddress, deliveryMethod,
                dropoffLat, dropoffLng, expectedSubtotal, expectedDeliveryFee, deliveryContactPhone, deliveryDetails, null, null,
                null, null);
    }
}
