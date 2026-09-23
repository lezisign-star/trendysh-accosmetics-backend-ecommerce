package com.nbh.backend.purchasing.service;

import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.purchasing.dto.PurchaseOrderDtos.CreateLineRequest;
import com.nbh.backend.purchasing.dto.PurchaseOrderDtos.CreatePurchaseOrderRequest;
import com.nbh.backend.purchasing.dto.PurchaseOrderDtos.PurchaseOrderDto;
import com.nbh.backend.purchasing.dto.PurchaseOrderDtos.ReceiveLineRequest;
import com.nbh.backend.purchasing.dto.PurchaseOrderDtos.ReceivePurchaseOrderRequest;
import com.nbh.backend.purchasing.dto.SupplierDtos.SupplierDto;
import com.nbh.backend.purchasing.dto.SupplierDtos.SupplierRequest;
import com.nbh.backend.purchasing.entity.PurchaseOrder;
import com.nbh.backend.purchasing.entity.PurchaseOrderLine;
import com.nbh.backend.purchasing.entity.PurchaseOrderStatus;
import com.nbh.backend.purchasing.entity.Supplier;
import com.nbh.backend.purchasing.repository.PurchaseOrderRepository;
import com.nbh.backend.purchasing.repository.SupplierRepository;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.repository.StockDocumentCounterRepository;
import com.nbh.backend.stock.service.StockDocumentNoGenerator;
import com.nbh.backend.stock.service.StockPostingService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.service.StaffBranchAccessService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Purchase orders: create against a supplier, send, receive (in full or in part — each receipt
 * posts a real GOODS_RECEIPT {@link StockDocument} through {@link StockPostingService}, the same
 * single write path the plain "just add stock" flow uses). This service never touches a stock
 * quantity itself — {@code SingleWritePathArchitectureTest} would fail the build if it tried.
 */
@Service
public class PurchaseOrderService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PurchaseOrderRepository purchaseOrders;
    private final SupplierRepository suppliers;
    private final ProductVariantRepository variants;
    private final StockLocationRepository locations;
    private final StockDocumentCounterRepository counters;
    private final StockPostingService postingService;
    private final StockDocumentNoGenerator documentNoGenerator;
    private final StaffBranchAccessService branchAccessService;
    private final Clock clock;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrders, SupplierRepository suppliers,
                                 ProductVariantRepository variants, StockLocationRepository locations,
                                 StockDocumentCounterRepository counters, StockPostingService postingService,
                                 StockDocumentNoGenerator documentNoGenerator,
                                 StaffBranchAccessService branchAccessService, Clock clock) {
        this.purchaseOrders = purchaseOrders;
        this.suppliers = suppliers;
        this.variants = variants;
        this.locations = locations;
        this.counters = counters;
        this.postingService = postingService;
        this.documentNoGenerator = documentNoGenerator;
        this.branchAccessService = branchAccessService;
        this.clock = clock;
    }

    // ---- suppliers ----

    @Transactional(readOnly = true)
    public List<SupplierDto> listSuppliers() {
        return suppliers.findByDeletedFalseOrderByName().stream().map(SupplierDto::from).toList();
    }

    @Transactional
    public SupplierDto createSupplier(SupplierRequest request) {
        Supplier supplier = new Supplier();
        applySupplierFields(supplier, request);
        return SupplierDto.from(suppliers.save(supplier));
    }

    @Transactional
    public SupplierDto updateSupplier(UUID id, SupplierRequest request) {
        Supplier supplier = suppliers.findById(id)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new NotFoundException("No supplier: " + id));
        applySupplierFields(supplier, request);
        return SupplierDto.from(suppliers.save(supplier));
    }

    @Transactional
    public void deleteSupplier(UUID id) {
        Supplier supplier = suppliers.findById(id)
                .orElseThrow(() -> new NotFoundException("No supplier: " + id));
        supplier.setDeleted(true);
        suppliers.save(supplier);
    }

    private void applySupplierFields(Supplier supplier, SupplierRequest request) {
        supplier.setName(request.name().trim());
        supplier.setContactName(blankToNull(request.contactName()));
        supplier.setPhone(blankToNull(request.phone()));
        supplier.setEmail(blankToNull(request.email()));
        supplier.setAddress(blankToNull(request.address()));
        supplier.setNotes(blankToNull(request.notes()));
    }

    // ---- purchase orders ----

    @Transactional(readOnly = true)
    public Page<PurchaseOrderDto> list(PurchaseOrderStatus status, Pageable pageable) {
        return purchaseOrders.search(status, pageable).map(PurchaseOrderDto::from);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDto get(UUID id) {
        return PurchaseOrderDto.from(findOrThrow(id));
    }

    @Transactional
    public PurchaseOrderDto create(CreatePurchaseOrderRequest request, StaffUser staff) {
        Supplier supplier = suppliers.findById(request.supplierId())
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new NotFoundException("No supplier: " + request.supplierId()));

        PurchaseOrder po = new PurchaseOrder();
        po.setPoNumber(nextPoNumber());
        po.setSupplier(supplier);
        po.setStatus(PurchaseOrderStatus.DRAFT);
        po.setExpectedDate(request.expectedDate());
        po.setNotes(request.notes());
        po.setCreatedByUserId(staff.getId());

        int lineNo = 1;
        for (CreateLineRequest line : request.lines()) {
            ProductVariant variant = variants.findById(line.variantId())
                    .orElseThrow(() -> new NotFoundException("No product variant: " + line.variantId()));
            PurchaseOrderLine poLine = new PurchaseOrderLine();
            poLine.setPurchaseOrder(po);
            poLine.setLineNo(lineNo++);
            poLine.setVariant(variant);
            poLine.setQtyOrdered(new BigDecimal(line.qty()));
            poLine.setUnitCost(new BigDecimal(line.unitCost()));
            po.getLines().add(poLine);
        }

        return PurchaseOrderDto.from(purchaseOrders.save(po));
    }

    @Transactional
    public PurchaseOrderDto markSent(UUID id) {
        PurchaseOrder po = findOrThrow(id);
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new UnprocessableException("Only a draft order can be marked sent");
        }
        po.setStatus(PurchaseOrderStatus.SENT);
        return PurchaseOrderDto.from(purchaseOrders.save(po));
    }

    @Transactional
    public PurchaseOrderDto cancel(UUID id) {
        PurchaseOrder po = findOrThrow(id);
        if (po.getStatus() != PurchaseOrderStatus.DRAFT && po.getStatus() != PurchaseOrderStatus.SENT) {
            throw new UnprocessableException("Only a draft or sent order can be cancelled");
        }
        boolean anythingReceived = po.getLines().stream().anyMatch(l -> l.getQtyReceived().signum() > 0);
        if (anythingReceived) {
            throw new UnprocessableException("Can't cancel — some lines already received stock");
        }
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        return PurchaseOrderDto.from(purchaseOrders.save(po));
    }

    /** Posts a real GOODS_RECEIPT stock document for the requested lines, then updates ordered-vs-received. */
    @Transactional
    public PurchaseOrderDto receive(UUID id, ReceivePurchaseOrderRequest request, StaffUser staff) {
        PurchaseOrder po = findOrThrow(id);
        if (po.getStatus() != PurchaseOrderStatus.SENT && po.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new UnprocessableException("Order must be sent before it can be received");
        }

        StockLocation target = findInternalActiveLocationOrThrow(request.locationId());
        branchAccessService.assertCanAccessBranch(staff, target.getBranch().getId());
        StockLocation supplierLocation = findLocationByCodeOrThrow("SUPPLIER");

        StockDocument document = new StockDocument();
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.GOODS_RECEIPT));
        document.setDocumentType(StockDocumentType.GOODS_RECEIPT);
        document.setBranch(target.getBranch());
        document.setNotes(request.notes());
        document.setPostedByUserId(staff.getId());
        document.setReferenceType("PURCHASE_ORDER");
        document.setReferenceId(po.getId());

        int lineNo = 1;
        for (ReceiveLineRequest reqLine : request.lines()) {
            PurchaseOrderLine poLine = po.getLines().stream()
                    .filter(l -> l.getId().equals(reqLine.poLineId()))
                    .findFirst()
                    .orElseThrow(() -> new UnprocessableException("Line " + reqLine.poLineId() + " isn't on this order"));

            BigDecimal qty = new BigDecimal(reqLine.qty());
            if (qty.signum() <= 0) {
                throw new UnprocessableException("Quantity must be positive");
            }
            BigDecimal remaining = poLine.getQtyOrdered().subtract(poLine.getQtyReceived());
            if (qty.compareTo(remaining) > 0) {
                throw new UnprocessableException(
                        poLine.getVariant().getSku() + ": only " + remaining + " left to receive");
            }

            StockDocumentLine docLine = new StockDocumentLine();
            docLine.setDocument(document);
            docLine.setLineNo(lineNo++);
            docLine.setVariant(poLine.getVariant());
            docLine.setSourceLocation(supplierLocation);
            docLine.setTargetLocation(target);
            docLine.setQty(qty);
            docLine.setUnitCost(poLine.getUnitCost());
            document.getLines().add(docLine);

            poLine.setQtyReceived(poLine.getQtyReceived().add(qty));
        }

        postingService.post(document);

        boolean fullyReceived = po.getLines().stream()
                .allMatch(l -> l.getQtyReceived().compareTo(l.getQtyOrdered()) >= 0);
        po.setStatus(fullyReceived ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED);

        return PurchaseOrderDto.from(purchaseOrders.save(po));
    }

    private String nextPoNumber() {
        String counterKey = "PO-" + LocalDate.now(clock).format(DATE);
        counters.upsertAndIncrement(counterKey);
        long sequence = counters.currentValue(counterKey);
        return counterKey + "-" + String.format("%06d", sequence);
    }

    private PurchaseOrder findOrThrow(UUID id) {
        return purchaseOrders.findById(id).orElseThrow(() -> new NotFoundException("No purchase order: " + id));
    }

    private StockLocation findInternalActiveLocationOrThrow(UUID id) {
        StockLocation location = locations.findById(id)
                .orElseThrow(() -> new NotFoundException("No stock location: " + id));
        if (!location.isInternal()) {
            throw new UnprocessableException(
                    "Location " + location.getCode() + " is virtual — receiving must target a physical location");
        }
        if (!location.isActive()) {
            throw new UnprocessableException("Location " + location.getCode() + " is not active");
        }
        return location;
    }

    private StockLocation findLocationByCodeOrThrow(String code) {
        return locations.findByCode(code)
                .orElseThrow(() -> new ConfigurationException("Missing seeded virtual location: " + code));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
