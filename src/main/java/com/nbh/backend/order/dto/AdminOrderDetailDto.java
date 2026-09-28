package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.payment.entity.PaymentRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal deliveryFee,
        BigDecimal grandTotal,
        Instant createdAt,
        Instant placedAt,
        String transporterName,
        String transporterPhone,
        BigDecimal agreedDeliveryPrice,
        List<Line> items,
        List<Payment> payments
) {
    public record Line(UUID variantId, String sku, String productName, String variantName,
                       BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {
        static Line from(CustomerOrderItem i) {
            return new Line(i.getVariant().getId(), i.getVariant().getSku(),
                    i.getVariant().getProduct().getName(), i.getVariant().getVariantName(),
                    i.getQty(), i.getUnitPrice(), i.getLineTotal());
        }
    }

    public record Payment(UUID id, PaymentRecord.PaymentMethod method, PaymentRecord.PaymentStatus status,
                          BigDecimal amount, String providerReference) {
        static Payment from(PaymentRecord p) {
            return new Payment(p.getId(), p.getMethod(), p.getStatus(), p.getAmount(), p.getProviderReference());
        }
    }

    public static AdminOrderDetailDto from(CustomerOrder o, List<PaymentRecord> payments) {
        return new AdminOrderDetailDto(
                o.getId(), o.getOrderNo(), o.getStatus(), o.getFulfilmentType().name(),
                o.getDeliveryMethod(), o.getCustomerName(), o.getGuestEmail(), o.getGuestPhone(), o.getDeliveryAddress(),
                o.getSubtotal(), o.getVatTotal(), o.getDeliveryFee(), o.getGrandTotal(),
                o.getCreatedAt(), o.getPlacedAt(),
                o.getTransporterName(), o.getTransporterPhone(), o.getAgreedDeliveryPrice(),
                o.getItems().stream().map(Line::from).toList(),
                payments.stream().map(Payment::from).toList());
    }
}
