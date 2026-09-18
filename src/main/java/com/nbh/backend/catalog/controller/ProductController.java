package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.dto.CatalogImageDto;
import com.nbh.backend.catalog.dto.CreateProductRequest;
import com.nbh.backend.catalog.dto.ProductDto;
import com.nbh.backend.catalog.dto.ProductVariantDto;
import com.nbh.backend.catalog.dto.UpdateProductRequest;
import com.nbh.backend.catalog.dto.UpdateVariantRequest;
import com.nbh.backend.catalog.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_VIEW') or hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping
    public Page<ProductDto> list(@PageableDefault(size = 20) Pageable pageable) {
        return productService.listProducts(pageable);
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
    @PostMapping("/{id}/images")
    public CatalogImageDto attachImage(@PathVariable UUID id, @Valid @RequestBody AttachImageRequest request) {
        return productService.attachImage(id, request);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @DeleteMapping("/{id}/images/{imageId}")
    public void removeImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        productService.removeImage(id, imageId);
    }
}
