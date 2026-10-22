package com.nbh.backend.order.dto;

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
     * later with the link, and set when staff are taking the payment now.
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
            @Valid PayNow payNow
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
}
