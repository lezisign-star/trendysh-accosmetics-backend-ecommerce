package com.nbh.backend.purchasing.dto;

import com.nbh.backend.purchasing.entity.PurchaseOrder;
import com.nbh.backend.purchasing.entity.PurchaseOrderLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PurchaseOrderDtos {

    private PurchaseOrderDtos() {}

    public record CreateLineRequest(
            @NotNull UUID variantId,
            @NotBlank String qty,
            @NotBlank String unitCost) {}

    public record CreatePurchaseOrderRequest(
            @NotNull UUID supplierId,
            LocalDate expectedDate,
            @Size(max = 1000) String notes,
            @NotEmpty @Valid List<CreateLineRequest> lines) {}

    public record ReceiveLineRequest(@NotNull UUID poLineId, @NotBlank String qty) {}

    public record ReceivePurchaseOrderRequest(
            @Size(max = 1000) String notes,
            @NotEmpty @Valid List<ReceiveLineRequest> lines) {}

    public record PurchaseOrderLineDto(
            UUID id, UUID variantId, String sku, String variantName,
            BigDecimal qtyOrdered, BigDecimal qtyReceived, BigDecimal unitCost) {

        public static PurchaseOrderLineDto from(PurchaseOrderLine l) {
            return new PurchaseOrderLineDto(l.getId(), l.getVariant().getId(), l.getVariant().getSku(),
                    l.getVariant().getVariantName(), l.getQtyOrdered(), l.getQtyReceived(), l.getUnitCost());
        }
    }

    public record PurchaseOrderDto(
            UUID id, String poNumber, UUID supplierId, String supplierName, String status,
            LocalDate expectedDate, String notes, Instant createdAt, List<PurchaseOrderLineDto> lines) {

        public static PurchaseOrderDto from(PurchaseOrder po) {
            return new PurchaseOrderDto(po.getId(), po.getPoNumber(), po.getSupplier().getId(),
                    po.getSupplier().getName(), po.getStatus().name(), po.getExpectedDate(), po.getNotes(),
                    po.getCreatedAt(), po.getLines().stream().map(PurchaseOrderLineDto::from).toList());
        }
    }
}
