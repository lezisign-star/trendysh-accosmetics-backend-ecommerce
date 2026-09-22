package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.dto.CatalogImageDto;
import com.nbh.backend.catalog.dto.CreateProductRequest;
import com.nbh.backend.catalog.dto.ProductDto;
import com.nbh.backend.catalog.dto.ProductVariantDto;
import com.nbh.backend.catalog.dto.UpdateProductRequest;
import com.nbh.backend.catalog.dto.UpdateVariantRequest;
import com.nbh.backend.catalog.service.ProductService;
import com.nbh.backend.catalog.service.VariantDealService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.service.VariantStockService;
import jakarta.validation.Valid;
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

    public ProductController(ProductService productService, VariantStockService variantStockService,
                              VariantDealService variantDealService) {
        this.productService = productService;
        this.variantStockService = variantStockService;
        this.variantDealService = variantDealService;
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
                                  @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return productService.listProducts(q, categoryId, published, stock, popular, newArrival, deleted, pageable);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_VIEW') or hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/{id}")
    public ProductDto get(@PathVariable UUID id) {
        return productService.getProduct(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping
    public ProductDto create(@Valid @RequestBody CreateProductRequest request) {
        return productService.createProduct(request);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PatchMapping("/{id}")
    public ProductDto update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/variants")
    public ProductVariantDto addVariant(@PathVariable UUID id,
                                         @Valid @RequestBody CreateProductRequest.CreateVariantRequest request) {
        return productService.addVariant(id, request);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PatchMapping("/{id}/variants/{variantId}")
    public ProductVariantDto updateVariant(@PathVariable UUID id, @PathVariable UUID variantId,
                                            @Valid @RequestBody UpdateVariantRequest request) {
        return productService.updateVariant(id, variantId, request);
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
    public List<CatalogImageDto> listImages(@PathVariable UUID id) {
        return productService.listImages(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/{id}/images/{imageId}/primary")
    public CatalogImageDto setPrimaryImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        return productService.setPrimaryImage(id, imageId);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/images")
    public CatalogImageDto attachImage(@PathVariable UUID id, @Valid @RequestBody AttachImageRequest request) {
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

    /**
     * Sets a variant's sellable stock to {@code quantity}; the difference is posted to the stock ledger
     * (see {@link VariantStockService}).
     */
    @PreAuthorize("hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/{id}/variants/{variantId}/stock")
    public VariantStockService.Result setStock(@PathVariable UUID id, @PathVariable UUID variantId,
                                                @Valid @RequestBody SetStockRequest request,
                                                @AuthenticationPrincipal StaffUser staff) {
        return variantStockService.setAvailable(id, variantId, request.quantity(), request.unitCost(), staff);
    }

    public record SetStockRequest(@jakarta.validation.constraints.NotNull BigDecimal quantity, BigDecimal unitCost) {}

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
}
