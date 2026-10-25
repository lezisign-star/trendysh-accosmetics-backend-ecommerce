package com.nbh.backend.order.service;

import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.order.dto.OrderReturnRequest;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.service.QuantityRules;
import com.nbh.backend.stock.service.StockDocumentNoGenerator;
import com.nbh.backend.stock.service.StockPostingService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Posts a customer return — the other half of {@link OrderFulfilmentService#fulfil}'s sale.
 * {@code StockPostingService.reverse} deliberately refuses a SALE_ONLINE document (see its
 * javadoc: "a return can be damaged-not-restocked; a plain reversal can't distinguish that"),
 * so this builds a fresh CUSTOMER_RETURN document instead of going through it, valuing the
 * physical leg at exactly what {@link CustomerOrderItem#getUnitCostAtSale()} recorded — not
 * today's moving average, which may have drifted since the sale.
 */
@Service
public class OrderReturnService {

    /** Only a dispatched/delivered order ever actually moved stock (see OrderFulfilmentService.fulfil) — anything earlier has nothing to return. */
    private static final Set<OrderStatus> RETURNABLE_STATUSES = EnumSet.of(OrderStatus.DISPATCHED, OrderStatus.DELIVERED);

    private final CustomerOrderRepository orderRepository;
    private final StockPostingService postingService;
    private final StockDocumentNoGenerator documentNoGenerator;

    public OrderReturnService(CustomerOrderRepository orderRepository,
                               StockPostingService postingService,
                               StockDocumentNoGenerator documentNoGenerator) {
        this.orderRepository = orderRepository;
        this.postingService = postingService;
        this.documentNoGenerator = documentNoGenerator;
    }

    @Transactional
    public CustomerOrder post(UUID orderId, OrderReturnRequest request) {
        CustomerOrder order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (!RETURNABLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException(
                    "Order " + order.getOrderNo() + " is " + order.getStatus() + " — nothing has shipped to return yet");
        }

        StockDocument document = new StockDocument();
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.CUSTOMER_RETURN));
        document.setDocumentType(StockDocumentType.CUSTOMER_RETURN);
        document.setReferenceType("CUSTOMER_ORDER");
        document.setReferenceId(order.getId());
        // StockDocument.reasonCode is a WriteOffReason enum, not a general field — a return's
        // free-text reason goes in notes instead.
        document.setNotes(request.reason() != null && !request.reason().isBlank()
                ? request.reason() + (request.notes() != null ? " — " + request.notes() : "")
                : request.notes());

        int lineNo = 1;
        for (OrderReturnRequest.Line reqLine : request.lines()) {
            CustomerOrderItem item = order.getItems().stream()
                    .filter(i -> i.getId().equals(reqLine.itemId()))
                    .findFirst()
                    .orElseThrow(() -> new UnprocessableException(
                            "Order " + order.getOrderNo() + " has no item " + reqLine.itemId()));
            if (item.getReservation() == null) {
                // A package's own summary line (or any line with no backing reservation) never posted
                // a stock movement of its own — see OrderFulfilmentService.fulfil's skip for the
                // same reason. Nothing to reverse here; its real components are separate lines.
                throw new UnprocessableException(
                        item.soldSku() + " never posted its own stock movement — return its components instead");
            }

            BigDecimal qty = new BigDecimal(reqLine.qty());
            if (qty.signum() <= 0) {
                throw new UnprocessableException(item.soldSku() + ": return qty must be positive");
            }
            QuantityRules.requireSensible(item.soldSku(), item.getVariant(), qty);
            BigDecimal outstanding = item.getQty().subtract(item.getReturnedQty());
            if (qty.compareTo(outstanding) > 0) {
                String name = item.soldProductName();
                throw new UnprocessableException(outstanding.signum() <= 0
                        ? name + ": all " + plain(item.getQty()) + " sold on this order have already been returned."
                        : name + ": only " + plain(outstanding) + " can still be returned (" + plain(item.getQty())
                                + " were sold, " + plain(item.getReturnedQty()) + " already came back).");
            }

            StockDocumentLine docLine = new StockDocumentLine();
            docLine.setDocument(document);
            docLine.setLineNo(lineNo++);
            docLine.setVariant(item.getVariant());
            docLine.setSourceAccount(StockAccount.CUSTOMER);
            docLine.setTargetAccount(reqLine.restock() ? StockAccount.SHOP : StockAccount.ADJUSTMENT);
            docLine.setQty(qty);
            // Valued at what this unit actually cost when it was sold, not today's average —
            // see this class's javadoc.
            docLine.setUnitCost(item.getUnitCostAtSale());
            document.getLines().add(docLine);

            item.setReturnedQty(item.getReturnedQty().add(qty));
        }

        postingService.post(document);
        return orderRepository.save(order);
    }

    /** A quantity as a person writes it: "2", not "2.000". */
    private static String plain(BigDecimal qty) {
        BigDecimal v = qty.stripTrailingZeros();
        return (v.scale() < 0 ? v.setScale(0) : v).toPlainString();
    }
}
