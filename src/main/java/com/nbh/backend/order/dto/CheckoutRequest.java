package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.FulfilmentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * {@code dropoffLat}/{@code dropoffLng} are the point the customer picked for a TumaBoda delivery. With
 * them (and the TumaBoda integration on) the order is booked through the API and the quoted fee is added
 * to the total; without them TumaBoda is arranged by phone as before.
 */
public record CheckoutRequest(
        @NotBlank @Size(max = 200) String customerName,
        @NotBlank @Email String guestEmail,
        @NotBlank String guestPhone,
        @NotNull FulfilmentType fulfilmentType,
        String deliveryAddress,
        @Pattern(regexp = "BODA|MATATU|COURIER", message = "deliveryMethod must be BODA, MATATU or COURIER") String deliveryMethod,
        BigDecimal dropoffLat,
        BigDecimal dropoffLng
) {
    /** The request as it was before the drop-off point existed: delivery arranged by phone. */
    public CheckoutRequest(String customerName, String guestEmail, String guestPhone, FulfilmentType fulfilmentType,
                           String deliveryAddress, String deliveryMethod) {
        this(customerName, guestEmail, guestPhone, fulfilmentType, deliveryAddress, deliveryMethod, null, null);
    }
}
