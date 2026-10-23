package com.nbh.backend.order.dto;

import com.nbh.backend.order.dto.PriceChangeDtos.PriceChangeInput;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.payment.entity.PaymentRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** An order that staff enter for a customer (owner, 2026-10-19). See {@code StaffOrderService}. */
public final class StaffOrderDtos {

    private StaffOrderDtos() {}

    /**
     * The lines are the staff member's own basket, built with the shop's ordinary basket endpoints under a session of their
     * own ({@code cartSession}), so every line is priced, kit-picked and stock-checked exactly as it is for a shopper.
     * {@code expectedSubtotal} is the total the screen showed: if the real total has moved since, nothing is placed.
     * {@code email} is optional (a customer on the phone or WhatsApp may have none). {@code deliveryAddress} is the shop
     * for a pickup and the place for a delivery, as at the checkout. {@code payNow} is null when the customer will pay
     * later with the link, and set when staff are taking the payment now. {@code priceChanges} are the lines whose price
     * staff changed (null or empty for none); {@code expectedSubtotal} is then the total with those changes in it.
     */
    public record StaffOrderRequest(
            @NotBlank @Size(max = 64) String cartSession,
            @NotBlank @Size(max = 200) String customerName,
            @NotBlank @Size(max = 20) String phone,
            @Email @Size(max = 255) String email,
            @NotNull FulfilmentType fulfilmentType,
            @Size(max = 2000) String deliveryAddress,
            @Pattern(regexp = "BODA|MATATU|COURIER", message = "deliveryMethod must be BODA, MATATU or COURIER") String deliveryMethod,
            @Size(max = 20) String deliveryContactPhone,
            @Valid DeliveryDetails deliveryDetails,
            @NotNull @PositiveOrZero BigDecimal expectedSubtotal,
            @Valid PayNow payNow,
            @Valid @Size(max = 50) List<@NotNull @Valid PriceChangeInput> priceChanges
    ) {}

    /** The payment staff are taking now. {@code reference} is the M-PESA code, bank reference or receipt number. */
    public record PayNow(
            @NotNull PaymentRecord.PaymentMethod method,
            @Size(max = 100) String reference,
            @Size(max = 300) String notes
    ) {}

    /**
     * The order as staff see it, and the way for the customer to pay: {@code payRef} is what opens the customer's pay page
     * ({@code /pay/{orderNo}?ref=…}); null when the order was paid at once or the shop does not collect by bank.
     */
    public record StaffOrderResponse(AdminOrderDetailDto order, UUID paymentRecordId, String payRef) {}

    /**
     * What the shop already knows about a customer who phones again, from the newest order placed with that number, so the
     * order being entered starts with their name, email and the place they last asked for. {@code found} is false (and
     * the rest empty) for a number that has never ordered; {@code orders} is how many times it has.
     */
    public record CustomerLookup(boolean found, String phone, String name, String email, long orders, String fulfilmentType,
                                 String deliveryAddress, String deliveryMethod, String deliveryContactPhone,
                                 DeliveryDetails deliveryDetails) {
        public static CustomerLookup none(String phone) {
            return new CustomerLookup(false, phone, null, null, 0, null, null, null, null, null);
        }
    }
}
