package com.nbh.backend.order.dto;

import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What staff need to actually process an order — customer contact, delivery
 * address, named line items and the payment record(s) — none of which the
 * shared {@link OrderDto} carries (it also backs guest tracking and the
 * checkout response, which shouldn't expose payment record ids).
 */
public record AdminOrderDetailDto(
        UUID id,
        String orderNo,
        OrderStatus status,
        String fulfilmentType,
        String deliveryMethod,
        String customerName,
        String guestEmail,
        String guestPhone,
        String deliveryAddress,
        /** Who the rider should actually call at drop-off, if different from {@code guestPhone}. Null means "same as above". */
        String deliveryContactPhone,
        /** The optional extras the customer gave about the delivery (building, directions, who receives it, ...); null when none. */
        DeliveryDetails deliveryDetails,
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal deliveryFee,
        BigDecimal grandTotal,
        Instant createdAt,
        Instant placedAt,
        String transporterName,
        String transporterPhone,
        BigDecimal agreedDeliveryPrice,
        /** Where the parcel was left, its receipt/waybill number and a note for the shopper; null until recorded. */
        String handoffStage,
        String handoffReference,
        String handoffNote,
        /** When the shopper was told which transporter has it and where to collect; null until then. */
        Instant customerInformedAt,
        Tumaboda tumaboda,
        List<Line> items,
        List<Payment> payments,
        /** The shopper account this order belongs to; null for a guest order. */
        Account account,
        /** The M-PESA codes the customer sent for this order, newest first. Empty means none has been entered yet. */
        List<Claim> claims,
        /** The member of staff who entered this order for a customer; null for an order the customer placed. */
        String placedBy,
        /** Lines whose price staff changed while entering the order (V72), with the reason and the verbal OK; empty for none. */
        List<PriceChangeDtos.PriceChangeView> priceChanges,
        /** A Metro's likely fare the shopper was shown, paid to the conductor (V82); null when none was known. */
        java.math.BigDecimal deliveryFareMin,
        java.math.BigDecimal deliveryFareMax
) {
    /**
     * Who the order is attached to, so staff can see at a glance whether it counts toward a customer's account
     * (the link coupons and points will be earned through). Null for a guest checkout.
     */
    public record Account(UUID id, String email, String fullName, boolean emailVerified) {
        static Account from(CustomerAccount a) {
            return a == null ? null : new Account(a.getId(), a.getEmail(), a.getFullName(), a.isEmailVerified());
        }
    }

    /** One M-PESA code a customer sent, and what staff did with it. */
    public record Claim(UUID id, String transactionCode, BigDecimal amountClaimed, String status, Instant createdAt,
                        String payerName, String resolvedBy, String resolutionNote) {
        static Claim from(PaymentClaim c) {
            return new Claim(c.getId(), c.getTransactionCode(), c.getAmountClaimed(), c.getStatus().name(),
                    c.getCreatedAt(), c.getPayerName(), c.getResolvedBy(), c.getResolutionNote());
        }
    }

    /**
     * The TumaBoda API side of the delivery, for the staff panel. Null when this order is not booked
     * through the API (phone-arranged, pickup, or rerouted back to manual — {@code rerouted} says which).
     * {@code needsAttention} is true when a booking is stuck or TumaBoda reports the delivery failed.
     */
    public record Tumaboda(boolean rerouted, boolean booked, String deliveryNumber, String status, String trackingUrl,
                           String trackingCode, String riderId, BigDecimal cost, BigDecimal customerPaid,
                           int bookingAttempts, Instant nextRetryAt, String bookingFailure, boolean needsAttention,
                           String pickupOtp) {
        static Tumaboda from(CustomerOrder o) {
            if (!o.isTumabodaApiDelivery() && !o.isTumabodaRerouted()) {
                return null;
            }
            boolean booked = o.getTumabodaDeliveryId() != null;
            boolean needsAttention = !o.isTumabodaRerouted()
                    && (CustomerOrder.isDeadTumabodaStatus(o.getTumabodaStatus())
                        || (!booked && o.getTumabodaBookingFailure() != null && o.getTumabodaNextRetryAt() == null));
            return new Tumaboda(o.isTumabodaRerouted(), booked, o.getTumabodaDeliveryNumber(), o.getTumabodaStatus(),
                    o.getTumabodaTrackingUrl(), o.getTumabodaTrackingCode(), o.getTumabodaRiderId(),
                    o.getTumabodaCost(), o.getDeliveryFee(), o.getTumabodaBookingAttempts(), o.getTumabodaNextRetryAt(),
                    o.getTumabodaBookingFailure(), needsAttention, o.getTumabodaPickupOtp());
        }
    }

    /**
     * {@code components} is non-empty only for a package's own summary line (one level deep — a package
     * can't contain another package, see {@code PackageService}). Each entry is a real constituent with its
     * own reservation, shown here purely for staff visibility; it's never counted again anywhere —
     * the parent line's own {@code lineTotal} (the package price) is what the order subtotal used.
     * {@code packedAt}/{@code packedBy} are the pick-and-pack tick: null until staff pack the line, and always null on
     * a package's own summary line (it is not picked, its products are).
     */
    public record Line(UUID itemId, UUID variantId, String sku, String productName, String variantName,
                       BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal, boolean wholesaleApplied,
                       BigDecimal returnedQty, List<Line> components,
                       /** The ordered colour/size/shade on its own, "Emerald Green"; null for a one-option product. */
                       String optionLabel,
                       /** The ordered colour on its own and the colour's dot ("#RRGGBB"); null when the variant had none. */
                       String optionColor, String swatchHex,
                       /** The ordered size on its own ("50ml"); null when the variant had none. */
                       String optionSize,
                       /** The photo the variant had when it was ordered; null for an order placed before photos were frozen. */
                       String imageUrl,
                       Instant packedAt, String packedBy) {
        /** Described from what the line froze at checkout (see {@link CustomerOrderItem#rememberVariant}). */
        static Line from(CustomerOrderItem i, List<CustomerOrderItem> children) {
            return new Line(i.getId(), i.getVariant().getId(), i.soldSku(),
                    i.soldProductName(), i.soldVariantName(),
                    i.getQty(), i.getUnitPrice(), i.getLineTotal(), i.isWholesaleApplied(),
                    i.getReturnedQty(),
                    children.stream().map(c -> Line.from(c, List.of())).toList(),
                    i.soldOptionLabel(), i.soldOptionColor(), i.soldSwatchHex(), i.soldOptionSize(),
                    i.soldImageUrl(), i.getPackedAt(), i.getPackedBy());
        }
    }

    public record Payment(UUID id, PaymentRecord.PaymentMethod method, PaymentRecord.PaymentStatus status,
                          BigDecimal amount, String providerReference) {
        static Payment from(PaymentRecord p) {
            return new Payment(p.getId(), p.getMethod(), p.getStatus(), p.getAmount(), p.getProviderReference());
        }
    }

    public static AdminOrderDetailDto from(CustomerOrder o, List<PaymentRecord> payments) {
        return from(o, payments, List.of());
    }

    public static AdminOrderDetailDto from(CustomerOrder o, List<PaymentRecord> payments, List<PaymentClaim> claims) {
        Map<CustomerOrderItem, List<CustomerOrderItem>> childrenByParent = o.getItems().stream()
                .filter(i -> i.getParentItem() != null)
                .collect(Collectors.groupingBy(CustomerOrderItem::getParentItem));
        List<Line> lines = o.getItems().stream()
                .filter(i -> i.getParentItem() == null)
                .map(i -> Line.from(i, childrenByParent.getOrDefault(i, List.of())))
                .toList();
        return new AdminOrderDetailDto(
                o.getId(), o.getOrderNo(), o.getStatus(), o.getFulfilmentType().name(),
                o.getDeliveryMethod(), o.getCustomerName(), o.getGuestEmail(), o.getGuestPhone(), o.getDeliveryAddress(),
                o.getDeliveryContactPhone(),
                DeliveryDetails.of(o),
                o.getSubtotal(), o.getVatTotal(), o.getDeliveryFee(), o.getGrandTotal(),
                o.getCreatedAt(), o.getPlacedAt(),
                o.getTransporterName(), o.getTransporterPhone(), o.getAgreedDeliveryPrice(),
                o.getHandoffStage(), o.getHandoffReference(), o.getHandoffNote(), o.getCustomerInformedAt(),
                Tumaboda.from(o),
                lines,
                payments.stream().map(Payment::from).toList(),
                Account.from(o.getCustomerAccount()),
                claims.stream().map(Claim::from).toList(),
                o.getPlacedBy(),
                List.of(),
                o.getDeliveryFareMin(), o.getDeliveryFareMax());
    }

    /** The same order with the price changes recorded on it (kept in their own table, so the factories above leave them out). */
    public AdminOrderDetailDto withPriceChanges(List<PriceChangeDtos.PriceChangeView> changes) {
        return new AdminOrderDetailDto(id, orderNo, status, fulfilmentType, deliveryMethod, customerName, guestEmail,
                guestPhone, deliveryAddress, deliveryContactPhone, deliveryDetails, subtotal, vatTotal, deliveryFee,
                grandTotal, createdAt, placedAt, transporterName, transporterPhone, agreedDeliveryPrice, handoffStage,
                handoffReference, handoffNote, customerInformedAt, tumaboda, items, payments, account, claims, placedBy,
                changes, deliveryFareMin, deliveryFareMax);
    }
}
