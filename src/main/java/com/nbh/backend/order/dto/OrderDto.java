package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.CustomerOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderDto(
        UUID id,
        String orderNo,
        String status,
        String fulfilmentType,
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal deliveryFee,
        BigDecimal grandTotal,
        List<OrderItemDto> items,
        java.time.Instant createdAt,
        String transporterName,
        String transporterPhone,
        BigDecimal agreedDeliveryPrice,
        /** TumaBoda's tracking link/code and its latest status for an order booked through the API; null otherwise. */
        String tumabodaTrackingUrl,
        String tumabodaTrackingCode,
        String tumabodaStatus
) {
    public static OrderDto from(CustomerOrder order) {
        return new OrderDto(
                order.getId(), order.getOrderNo(), order.getStatus().name(), order.getFulfilmentType().name(),
                order.getSubtotal(), order.getVatTotal(),
                order.getDeliveryFee(), order.getGrandTotal(),
                order.getItems().stream().map(OrderItemDto::from).toList(),
                order.getCreatedAt(),
                order.getTransporterName(), order.getTransporterPhone(), order.getAgreedDeliveryPrice(),
                order.getTumabodaTrackingUrl(), order.getTumabodaTrackingCode(), order.getTumabodaStatus());
    }
}
