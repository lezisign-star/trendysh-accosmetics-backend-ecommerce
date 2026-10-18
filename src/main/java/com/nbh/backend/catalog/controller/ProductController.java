package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.catalog.dto.AssignImageRequest;
import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.dto.BulkProductActionRequest;
import com.nbh.backend.catalog.dto.BulkProductActionResult;
import com.nbh.backend.catalog.dto.ProductDto;
import com.nbh.backend.catalog.dto.ProductVariantDto;
import com.nbh.backend.catalog.dto.RenameProductRequest;
import com.nbh.backend.catalog.dto.SaveProductRequest;
import com.nbh.backend.catalog.service.ProductService;
import com.nbh.backend.catalog.service.VariantDealService;
import com.nbh.backend.catalog.service.WholesalePricingService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.service.VariantStockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;
    private final VariantStockService variantStockService;
    private final VariantDealService variantDealService;
    private final WholesalePricingService wholesalePricingService;

    public ProductController(ProductService productService, VariantStockService variantStockService,
                              VariantDealService variantDealService,
                              WholesalePricingService wholesalePricingService) {
        this.productService = productService;
        this.variantStockService = variantStockService;
        this.variantDealService = variantDealService;
        this.wholesalePricingService = wholesalePricingService;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_VIEW') or hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping
    public Page<ProductDto> list(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) UUID categoryId,
                                  @RequestParam(required = false) Boolean published,
                                  @RequestParam(required = false) String stock,
                                  @RequestParam(required = false) Boolean popular,
                                  @RequestParam(name = "newArrival", required = false) Boolean newArrival,
                                  @RequestParam(defaultValue = "false") boolean deleted,
                                  @RequestParam(required = false) String options,
                                  @RequestParam(defaultValue = "false") boolean noPhoto,
                                  @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return productService.listProducts(q, categoryId, published, stock, popular, newArrival, deleted, options,
                noPhoto, pageable);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_VIEW') or hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/{id}")
    public ProductDto get(@PathVariable UUID id) {
        return productService.getProduct(id);
    }

    /** The one-page editor's single Save, for a brand-new draft (Phase 3, 2026-09-24). */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/draft")
    public ProductDto createDraft(@RequestBody SaveProductRequest request, @AuthenticationPrincipal StaffUser staff) {
        return productService.saveProduct(null, request, staff);
    }

    /** The one-page editor's single Save, for an existing product. See {@link SaveProductRequest}'s javadoc. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/{id}/full")
    public ProductDto saveFull(@PathVariable UUID id, @RequestBody SaveProductRequest request,
                                @AuthenticationPrincipal StaffUser staff) {
        return productService.saveProduct(id, request, staff);
    }

    /**
     * Renames a product and nothing else: the editor's name autosave. See {@link ProductService#rename}: it cannot
     * change the shop switch, and it refuses a product that is in the shop.
     */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PatchMapping("/{id}/name")
    public ProductDto rename(@PathVariable UUID id, @RequestBody RenameProductRequest request,
                             @AuthenticationPrincipal StaffUser staff) {
        return productService.rename(id, request, staff);
    }

    /** A fresh, unpublished copy — fast entry of many similar products (Phase 3, 2026-09-24). */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/duplicate")
    public ProductDto duplicate(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        return productService.duplicate(id, staff);
    }

    /** The admin product list's bulk-action bar (Phase 3, 2026-09-24). */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/bulk")
    public BulkProductActionResult bulk(@RequestBody BulkProductActionRequest request) {
        return productService.bulk(request);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/publish")
    public ProductDto publish(@PathVariable UUID id) {
        return productService.setPublished(id, true);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/unpublish")
    public ProductDto unpublish(@PathVariable UUID id) {
        return productService.setPublished(id, false);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/{id}/images")
    public List<AdminImageDto> listImages(@PathVariable UUID id) {
        return productService.listImages(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/{id}/images/{imageId}/primary")
    public AdminImageDto setPrimaryImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        return productService.setPrimaryImage(id, imageId);
    }

    /** Gives a photo to one option (a photo belongs to exactly one): how an unassigned or misplaced photo gets its owner. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/{id}/images/{imageId}/option")
    public AdminImageDto assignImage(@PathVariable UUID id, @PathVariable UUID imageId,
                                     @Valid @RequestBody AssignImageRequest request) {
        return productService.assignImage(id, imageId, request.variantId());
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/images")
    public AdminImageDto attachImage(@PathVariable UUID id, @Valid @RequestBody AttachImageRequest request) {
        return productService.attachImage(id, request);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @DeleteMapping("/{id}/images/{imageId}")
    public void removeImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        productService.removeImage(id, imageId);
    }

    /** Soft delete — reversible via {@code /restore}; history (orders, stock movements) is untouched. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @DeleteMapping("/{id}")
    public ProductDto delete(@PathVariable UUID id) {
        return productService.softDelete(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/restore")
    public ProductDto restore(@PathVariable UUID id) {
        return productService.restore(id);
    }

    /** Saves one variant on its own: its name, description, price, on-sale switch, and colour and size when sent. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/{id}/variants/{variantId}")
    public ProductVariantDto saveVariant(@PathVariable UUID id, @PathVariable UUID variantId,
                                         @RequestBody SaveProductRequest.VariantInput request) {
        return productService.saveVariant(id, variantId, request);
    }

    /**
     * Sets a variant's physical on-hand stock to {@code quantity} — "we counted N on the shelf" — or, when
     * {@code receivedQty} is sent instead, adds that many to what is on the shelf right now ("12 more came in").
     * The difference is posted to the stock ledger (see {@link VariantStockService}).
     */
    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/{id}/variants/{variantId}/stock")
    public VariantStockService.Result setStock(@PathVariable UUID id, @PathVariable UUID variantId,
                                                @Valid @RequestBody SetStockRequest request,
                                                @AuthenticationPrincipal StaffUser staff) {
        boolean allowUnknownCost = Boolean.TRUE.equals(request.allowUnknownCost());
        if (request.receivedQty() != null) {
            if (request.quantity() != null) {
                throw new IllegalArgumentException("Send either the new count or the number received, not both");
            }
            return variantStockService.addOnHand(id, variantId, request.receivedQty(), request.unitCost(),
                    request.reason(), allowUnknownCost, staff);
        }
        if (request.quantity() == null) {
            throw new IllegalArgumentException("Send the new count (quantity) or the number received (receivedQty)");
        }
        return variantStockService.setOnHand(id, variantId, request.quantity(), request.unitCost(), request.reason(),
                allowUnknownCost, staff);
    }

    /**
     * Exactly one of {@code quantity} (the count) and {@code receivedQty} (how many came in). {@code allowUnknownCost}:
     * see {@link VariantStockService#setOnHand(UUID, UUID, BigDecimal, BigDecimal, String, boolean, StaffUser)}.
     */
    public record SetStockRequest(
            BigDecimal quantity, BigDecimal unitCost, String reason, Boolean allowUnknownCost,
            BigDecimal receivedQty) {}

    /** Sets a variant's flash-deal price + deadline. See {@link VariantDealService}'s javadoc for the validation rules. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/{id}/variants/{variantId}/deal")
    @org.springframework.transaction.annotation.Transactional
    public ProductVariantDto setDeal(@PathVariable UUID id, @PathVariable UUID variantId,
                                      @Valid @RequestBody SetDealRequest request) {
        return ProductVariantDto.from(variantDealService.setDeal(id, variantId, request.dealPrice(), request.dealEndsAt()));
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @DeleteMapping("/{id}/variants/{variantId}/deal")
    @org.springframework.transaction.annotation.Transactional
    public ProductVariantDto clearDeal(@PathVariable UUID id, @PathVariable UUID variantId) {
        return ProductVariantDto.from(variantDealService.clearDeal(id, variantId));
    }

    public record SetDealRequest(@NotNull BigDecimal dealPrice, @NotNull Instant dealEndsAt) {}

    /** Sets a variant's wholesale tier. See {@link WholesalePricingService}'s javadoc for the validation rules. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/{id}/variants/{variantId}/wholesale")
    @org.springframework.transaction.annotation.Transactional
    public ProductVariantDto setWholesalePrice(@PathVariable UUID id, @PathVariable UUID variantId,
                                                @Valid @RequestBody SetWholesaleRequest request) {
        return ProductVariantDto.from(wholesalePricingService.setWholesalePrice(
                id, variantId, request.minQty(), request.wholesalePrice()));
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @DeleteMapping("/{id}/variants/{variantId}/wholesale")
    @org.springframework.transaction.annotation.Transactional
    public ProductVariantDto clearWholesalePrice(@PathVariable UUID id, @PathVariable UUID variantId) {
        return ProductVariantDto.from(wholesalePricingService.clearWholesalePrice(id, variantId));
    }

    public record SetWholesaleRequest(@NotNull @Min(2) Integer minQty, @NotNull BigDecimal wholesalePrice) {}
}
