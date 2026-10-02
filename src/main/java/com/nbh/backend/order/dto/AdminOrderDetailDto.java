package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
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
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal deliveryFee,
        BigDecimal grandTotal,
        Instant createdAt,
        Instant placedAt,
        String transporterName,
        String transporterPhone,
        BigDecimal agreedDeliveryPrice,
        Tumaboda tumaboda,
        List<Line> items,
        List<Payment> payments
) {
    /**
     * The TumaBoda API side of the delivery, for the staff panel. Null when this order is not booked
     * through the API (phone-arranged, pickup, or rerouted back to manual — {@code rerouted} says which).
     * {@code needsAttention} is true when a booking is stuck or TumaBoda reports the delivery failed.
     */
    public record Tumaboda(boolean rerouted, boolean booked, String deliveryNumber, String status, String trackingUrl,
                           String trackingCode, String riderId, BigDecimal cost, BigDecimal customerPaid,
                           int bookingAttempts, Instant nextRetryAt, String bookingFailure, boolean needsAttention) {
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
                    o.getTumabodaBookingFailure(), needsAttention);
        }
    }

    /**
     * {@code components} is non-empty only for a kit's own summary line (one level deep — a kit
     * can't contain another kit, see {@code KitService}). Each entry is a real constituent with its
     * own reservation, shown here purely for staff visibility; it's never counted again anywhere —
     * the parent line's own {@code lineTotal} (the package price) is what the order subtotal used.
     */
    public record Line(UUID itemId, UUID variantId, String sku, String productName, String variantName,
                       BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal, boolean wholesaleApplied,
                       BigDecimal returnedQty, List<Line> components) {
        static Line from(CustomerOrderItem i, List<CustomerOrderItem> children) {
            return new Line(i.getId(), i.getVariant().getId(), i.getVariant().getSku(),
                    i.getVariant().getProduct().getName(), i.getVariant().getVariantName(),
                    i.getQty(), i.getUnitPrice(), i.getLineTotal(), i.isWholesaleApplied(),
                    i.getReturnedQty(),
                    children.stream().map(c -> Line.from(c, List.of())).toList());
        }
    }

    public record Payment(UUID id, PaymentRecord.PaymentMethod method, PaymentRecord.PaymentStatus status,
                          BigDecimal amount, String providerReference) {
        static Payment from(PaymentRecord p) {
            return new Payment(p.getId(), p.getMethod(), p.getStatus(), p.getAmount(), p.getProviderReference());
        }
    }

    public static AdminOrderDetailDto from(CustomerOrder o, List<PaymentRecord> payments) {
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
                o.getSubtotal(), o.getVatTotal(), o.getDeliveryFee(), o.getGrandTotal(),
                o.getCreatedAt(), o.getPlacedAt(),
                o.getTransporterName(), o.getTransporterPhone(), o.getAgreedDeliveryPrice(),
                Tumaboda.from(o),
                lines,
                payments.stream().map(Payment::from).toList());
    }
}
