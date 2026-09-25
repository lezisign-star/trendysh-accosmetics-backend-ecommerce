package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.dto.BulkProductActionRequest;
import com.nbh.backend.catalog.dto.BulkProductActionResult;
import com.nbh.backend.catalog.dto.CatalogImageDto;
import com.nbh.backend.catalog.dto.CreateProductRequest;
import com.nbh.backend.catalog.dto.ProductDto;
import com.nbh.backend.catalog.dto.ProductVariantDto;
import com.nbh.backend.catalog.dto.SaveProductRequest;
import com.nbh.backend.catalog.dto.UpdateProductRequest;
import com.nbh.backend.catalog.dto.UpdateVariantRequest;
import com.nbh.backend.catalog.entity.*;
import com.nbh.backend.catalog.repository.*;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Product/variant creation — the "easy to add a product" flow the founder
 * called out as a priority. One request creates the product template plus
 * every initial variant/SKU in a single transaction; variants and barcodes
 * can also be added one at a time later via the narrower endpoints.
 *
 * Deliberately does NOT touch stock — a newly created variant has zero stock
 * everywhere until a GOODS_RECEIPT is posted through StockPostingService.
 * Product creation and stock receipt are two different real-world events and
 * two different permissions (PRODUCT_MANAGE vs STOCK_RECEIVE).
 */
@Service
public class ProductService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final ProductAttributeValueRepository attributeValueRepository;
    private final VariantAttributeValueRepository variantAttributeValueRepository;
    private final VariantBarcodeRepository variantBarcodeRepository;
    private final ProductImageRepository imageRepository;
    private final StockBalanceRepository balanceRepository;

    public ProductService(ProductRepository productRepository,
                           ProductVariantRepository variantRepository,
                           BrandRepository brandRepository,
                           CategoryRepository categoryRepository,
                           ProductAttributeValueRepository attributeValueRepository,
                           VariantAttributeValueRepository variantAttributeValueRepository,
                           VariantBarcodeRepository variantBarcodeRepository,
                           ProductImageRepository imageRepository,
                           StockBalanceRepository balanceRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.brandRepository = brandRepository;
        this.categoryRepository = categoryRepository;
        this.attributeValueRepository = attributeValueRepository;
        this.variantAttributeValueRepository = variantAttributeValueRepository;
        this.variantBarcodeRepository = variantBarcodeRepository;
        this.imageRepository = imageRepository;
        this.balanceRepository = balanceRepository;
    }

    @Transactional
    public ProductDto createProduct(CreateProductRequest request) {
        Product product = new Product();
        product.setName(request.name());
        product.setSlug(generateUniqueSlug(request.name()));
        product.setShortDescription(request.shortDescription());
        product.setDescription(request.description());
        product.setIngredients(request.ingredients());
        product.setHowToUse(request.howToUse());
        product.setPublished(false); // staff publishes explicitly once photos/pricing are ready
        product.setPopular(request.isPopular() != null && request.isPopular());
        product.setNewArrival(request.isNewArrival() != null && request.isNewArrival());

        if (request.brandId() != null) {
            product.setBrand(brandRepository.findById(request.brandId())
                    .orElseThrow(() -> new EntityNotFoundException("No brand: " + request.brandId())));
        }
        applyCategories(product, request.categoryId(), request.subcategoryId());

        Product savedProduct = productRepository.save(product);

        List<ProductVariantDto> variantDtos = request.variants().stream()
                .map(vr -> createVariant(savedProduct, vr))
                .toList();

        return ProductDto.from(savedProduct, variantDtos);
    }

    /**
     * Updates a product's own fields. {@code name}/{@code brandId}/category-subcategory are
     * full-replace (send what you want, null clears it — their established contract). The text
     * fields and the two merchandising flags are merge-only (null = leave unchanged) — see
     * {@link UpdateProductRequest}'s javadoc for why: a plain full-replace here used to let the
     * admin's Quick-edit panel silently wipe a product's description/ingredients/how-to-use to
     * null on every quick save, since that panel only ever sends a handful of fields. Found live
     * 2026-09-22, fixed here rather than by making every caller resend every field, which would
     * only invite the same bug again the next time a new partial-save screen is added.
     */
    @Transactional
    public ProductDto updateProduct(UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));

        product.setName(request.name());
        if (request.shortDescription() != null) product.setShortDescription(request.shortDescription());
        if (request.description() != null) product.setDescription(request.description());
        if (request.ingredients() != null) product.setIngredients(request.ingredients());
        if (request.howToUse() != null) product.setHowToUse(request.howToUse());
        if (request.isPopular() != null) product.setPopular(request.isPopular());
        if (request.isNewArrival() != null) product.setNewArrival(request.isNewArrival());
        product.setBrand(request.brandId() != null
                ? brandRepository.findById(request.brandId())
                        .orElseThrow(() -> new EntityNotFoundException("No brand: " + request.brandId()))
                : null);
        applyCategories(product, request.categoryId(), request.subcategoryId());

        productRepository.save(product);
        return ProductDto.from(product, variantDtos(productId), primaryImageUrl(productId));
    }

    /**
     * Compatibility path for the pre-Phase-3 category/subcategory pair (old create/update
     * endpoints, still used by the old frontend until Phase 3c ships) — translates it into the
     * new many-to-many {@code categories} list. A subcategory is more specific than its category,
     * so it becomes the one stored (and primary, position 0); a category alone is fine; neither
     * clears the list. Removed in Phase 3f once nothing calls the old endpoints any more.
     */
    private void applyCategories(Product product, UUID categoryId, UUID subcategoryId) {
        Category category = categoryId != null
                ? categoryRepository.findById(categoryId)
                        .orElseThrow(() -> new EntityNotFoundException("No category: " + categoryId))
                : null;
        Category subcategory = subcategoryId != null
                ? categoryRepository.findById(subcategoryId)
                        .orElseThrow(() -> new EntityNotFoundException("No subcategory: " + subcategoryId))
                : null;
        if (subcategory != null) {
            Category parent = subcategory.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("\"" + subcategory.getName() + "\" is a top-level category, not a subcategory");
            }
            if (category == null) {
                category = parent;
            } else if (!parent.getId().equals(category.getId())) {
                throw new IllegalArgumentException(
                        "\"" + subcategory.getName() + "\" is not a subcategory of \"" + category.getName() + "\"");
            }
        }
        if (category != null && category.getParent() != null) {
            throw new IllegalArgumentException("\"" + category.getName() + "\" is a subcategory - choose it as the subcategory instead");
        }
        List<Category> categories = new java.util.ArrayList<>();
        if (subcategory != null) {
            categories.add(subcategory);
        } else if (category != null) {
            categories.add(category);
        }
        product.setCategories(categories);
    }

    /**
     * The new Phase 3 (2026-09-24) multi-category path: a product can belong to several
     * categories, in the given order — the first is "primary" (breadcrumbs, SKU prefix).
     */
    private void setCategories(Product product, List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            product.setCategories(new java.util.ArrayList<>());
            return;
        }
        List<Category> categories = new java.util.ArrayList<>();
        for (UUID id : categoryIds) {
            categories.add(categoryRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("No category: " + id)));
        }
        product.setCategories(categories);
    }

    /** A product's live (non-deleted) variants with their sellable stock and selected attribute values filled in. */
    private List<ProductVariantDto> variantDtos(UUID productId) {
        List<ProductVariant> variants = variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        List<UUID> variantIds = variants.stream().map(ProductVariant::getId).toList();
        Map<UUID, BigDecimal> available = availability(variantIds);
        List<VariantAttributeValue> attrs = attributesFor(variantIds);
        return variants.stream()
                .map(v -> ProductVariantDto.from(v, available.getOrDefault(v.getId(), BigDecimal.ZERO), attrs))
                .toList();
    }

    private List<VariantAttributeValue> attributesFor(Collection<UUID> variantIds) {
        return variantIds.isEmpty() ? List.of() : variantAttributeValueRepository.findByVariantIdIn(variantIds);
    }

    private Map<UUID, BigDecimal> availability(Collection<UUID> variantIds) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        if (variantIds.isEmpty()) return result;
        for (StockBalanceRepository.VariantAvailability a : balanceRepository.availabilityForVariants(variantIds)) {
            result.put(a.getVariantId(), a.getAvailable());
        }
        return result;
    }

    /**
     * The one-page editor's single Save (Phase 3, 2026-09-24). Creates a new draft when
     * {@code productId} is null, otherwise updates in place. Always saves — publishing with
     * missing fields only produces {@link ProductDto#warnings()} in the response, it never
     * blocks (founder decision: "drafts save anytime, only warn at publish"). Photos still go
     * through the existing dedicated image endpoints, not this one — no behaviour change there,
     * just not folded into this request to keep it manageable.
     */
    @Transactional
    public ProductDto saveProduct(UUID productId, SaveProductRequest request, StaffUser staff) {
        Product product;
        if (productId == null) {
            product = new Product();
            String draftName = request.name() == null || request.name().isBlank() ? "product" : request.name();
            product.setSlug(generateUniqueSlug(draftName));
            product.setPublished(false);
            product.setCreatedBy(staff != null ? staff.getId() : null);
        } else {
            product = productRepository.findById(productId)
                    .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
            // Truncated to whole seconds on both sides — found live: the JVM auditing clock sets
            // updatedAt with nanosecond precision, but Postgres's timestamptz only keeps
            // microseconds and the JDBC driver ROUNDS (not truncates) to the nearest one on
            // write. A value like .022999900 can round up to .023000000 on storage, so even
            // truncating both sides to microseconds still falsely conflicted right at that
            // boundary. A same-second double-save by two different staff going undetected is an
            // acceptable trade-off for a low-traffic admin tool — the alternative was a spurious
            // 409 on a large fraction of ordinary solo saves.
            if (request.expectedUpdatedAt() != null
                    && !request.expectedUpdatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                            .equals(product.getUpdatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
                throw new ConflictException("Someone else changed this product — reload to see their changes");
            }
        }

        product.setName(request.name() == null ? "" : request.name());
        product.setDescription(request.description());
        product.setShortDescription(request.shortDescription());
        product.setIngredients(request.ingredients());
        product.setHowToUse(request.howToUse());
        if (request.isPopular() != null) product.setPopular(request.isPopular());
        if (request.isNewArrival() != null) product.setNewArrival(request.isNewArrival());
        product.setUpdatedBy(staff != null ? staff.getId() : null);
        setCategories(product, request.categoryIds());

        boolean wasPublished = product.isPublished();
        product.setPublished(request.published());
        if (request.published() && !wasPublished) {
            product.setPublishedAt(Instant.now());
        } else if (!request.published()) {
            product.setPublishedAt(null);
        }

        product = productRepository.save(product);
        saveVariants(product, request.variants() == null ? List.of() : request.variants());

        List<ProductVariantDto> variants = variantDtos(product.getId());
        return ProductDto.from(product, variants, primaryImageUrl(product.getId()), warnings(product, variants));
    }

    /**
     * Diffs the request's variant list against what's stored: a variant with an {@code id}
     * already on this product is updated, one with no {@code id} is created (with an
     * auto-generated SKU — SKUs are never user-editable, see {@link #generateSku}), and one
     * that's on the product but missing from the request is soft-deleted. Order becomes
     * {@code sortOrder}.
     */
    private void saveVariants(Product product, List<SaveProductRequest.VariantInput> inputs) {
        List<ProductVariant> existing = variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId());
        Map<UUID, ProductVariant> byId = existing.stream().collect(Collectors.toMap(ProductVariant::getId, v -> v));
        Set<UUID> keep = new HashSet<>();

        int order = 0;
        for (SaveProductRequest.VariantInput in : inputs) {
            ProductVariant variant;
            if (in.id() != null && byId.containsKey(in.id())) {
                variant = byId.get(in.id());
            } else {
                variant = new ProductVariant();
                variant.setProduct(product);
                variant.setSku(generateSku(product));
                variant.setVatExempt(false);
            }
            variant.setVariantName(in.variantName() == null ? "" : in.variantName());
            variant.setDescription(in.description());
            variant.setSellingPrice(parsePriceOrNull(in.sellingPrice()));
            variant.setActive(in.isActive());
            variant.setSortOrder(order++);
            variant = variantRepository.save(variant);
            keep.add(variant.getId());
        }

        for (ProductVariant v : existing) {
            if (!keep.contains(v.getId())) {
                v.setDeleted(true);
                v.setActive(false);
                variantRepository.save(v);
            }
        }
    }

    private BigDecimal parsePriceOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + raw + "\" is not a valid price");
        }
    }

    /**
     * Plain-language readiness hints for the editor's publish-warning dialog — never blocks a
     * save, only informs (founder decision, Phase 3, 2026-09-24). Computed fresh on every
     * save/read so the frontend never has to mirror this logic to stay correct, only to give
     * live hints as the admin types.
     */
    private List<String> warnings(Product product, List<ProductVariantDto> variants) {
        List<String> warnings = new ArrayList<>();
        if (product.getName() == null || product.getName().isBlank()) warnings.add("No name yet");
        if (product.getCategories().isEmpty()) warnings.add("Not in any category");
        if (primaryImageUrl(product.getId()) == null) warnings.add("No photo");

        long noPriceCount = variants.stream().filter(v -> v.sellingPrice() == null).count();
        if (noPriceCount > 0) {
            warnings.add(noPriceCount + (noPriceCount == 1 ? " variant has" : " variants have")
                    + " no price — " + (noPriceCount == 1 ? "it won't" : "they won't") + " show in the shop");
        }

        if (variants.size() > 1) {
            Set<String> seen = new HashSet<>();
            Set<String> dupes = new LinkedHashSet<>();
            for (ProductVariantDto v : variants) {
                String name = v.variantName() == null ? "" : v.variantName().trim().toLowerCase(Locale.ROOT);
                if (!name.isBlank() && !seen.add(name)) dupes.add(v.variantName());
            }
            dupes.forEach(name -> warnings.add("Two variants are both called \"" + name + "\""));

            if (variants.stream().anyMatch(v -> v.variantName() == null || v.variantName().isBlank())) {
                warnings.add("A variant needs a name");
            }
        }
        return warnings;
    }

    /** A fresh, unpublished copy — "Name (copy)" — for fast entry of many similar products (founder priority, Phase 3). */
    @Transactional
    public ProductDto duplicate(UUID productId, StaffUser staff) {
        Product source = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));

        Product copy = new Product();
        copy.setName(source.getName() + " (copy)");
        copy.setSlug(generateUniqueSlug(copy.getName()));
        copy.setBrand(source.getBrand());
        copy.setCategories(new ArrayList<>(source.getCategories()));
        copy.setShortDescription(source.getShortDescription());
        copy.setDescription(source.getDescription());
        copy.setIngredients(source.getIngredients());
        copy.setHowToUse(source.getHowToUse());
        copy.setPublished(false);
        copy.setCreatedBy(staff != null ? staff.getId() : null);
        copy = productRepository.save(copy);

        for (ProductVariant sv : variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId)) {
            ProductVariant nv = new ProductVariant();
            nv.setProduct(copy);
            nv.setSku(generateSku(copy));
            nv.setVariantName(sv.getVariantName());
            nv.setDescription(sv.getDescription());
            nv.setSellingPrice(sv.getSellingPrice());
            nv.setCompareAtPrice(sv.getCompareAtPrice());
            nv.setStandardCost(sv.getStandardCost());
            nv.setUom(sv.getUom());
            nv.setVatRate(sv.getVatRate());
            nv.setVatExempt(sv.isVatExempt());
            nv.setReorderLevel(sv.getReorderLevel());
            nv.setSortOrder(sv.getSortOrder());
            nv.setActive(sv.isActive());
            variantRepository.save(nv);
        }

        for (ProductImage si : imageRepository.findByProductIdOrderBySortOrderAsc(productId)) {
            ProductImage ni = new ProductImage();
            ni.setProduct(copy);
            ni.setUrl(si.getUrl());
            ni.setThumbnailUrl(si.getThumbnailUrl());
            ni.setAltText(si.getAltText());
            ni.setWidth(si.getWidth());
            ni.setHeight(si.getHeight());
            ni.setByteSize(si.getByteSize());
            ni.setSortOrder(si.getSortOrder());
            ni.setPrimary(si.isPrimary());
            imageRepository.save(ni);
        }

        List<ProductVariantDto> variants = variantDtos(copy.getId());
        return ProductDto.from(copy, variants, primaryImageUrl(copy.getId()), warnings(copy, variants));
    }

    /**
     * The admin product list's bulk-action bar (Phase 3, 2026-09-24) — one transaction, one
     * action, across every selected product. PUBLISH still only warns, never blocks (same rule
     * as a single product's save).
     */
    @Transactional
    public BulkProductActionResult bulk(BulkProductActionRequest request) {
        List<Product> products = productRepository.findAllById(request.productIds());
        List<String> warnings = new ArrayList<>();
        int affectedVariants = 0;

        switch (request.action()) {
            case PUBLISH -> {
                for (Product p : products) {
                    List<ProductVariantDto> variants = variantDtos(p.getId());
                    List<String> w = warnings(p, variants);
                    if (!w.isEmpty()) warnings.add("\"" + p.getName() + "\": " + String.join("; ", w));
                    p.setPublished(true);
                    p.setPublishedAt(Instant.now());
                    productRepository.save(p);
                }
            }
            case UNPUBLISH -> {
                for (Product p : products) {
                    p.setPublished(false);
                    p.setPublishedAt(null);
                    productRepository.save(p);
                }
            }
            case DELETE -> {
                for (Product p : products) {
                    p.setDeleted(true);
                    p.setPublished(false);
                    p.setPublishedAt(null);
                    productRepository.save(p);
                }
            }
            case RESTORE -> {
                for (Product p : products) {
                    p.setDeleted(false);
                    productRepository.save(p);
                }
            }
            case ADD_CATEGORIES, REPLACE_CATEGORIES -> {
                List<Category> toAdd = resolveCategories(request.categoryIds());
                for (Product p : products) {
                    if (request.action() == BulkProductActionRequest.Action.REPLACE_CATEGORIES) {
                        p.setCategories(new ArrayList<>(toAdd));
                    } else {
                        List<Category> merged = new ArrayList<>(p.getCategories());
                        Set<UUID> existingIds = merged.stream().map(Category::getId).collect(Collectors.toSet());
                        for (Category c : toAdd) {
                            if (existingIds.add(c.getId())) merged.add(c);
                        }
                        p.setCategories(merged);
                    }
                    productRepository.save(p);
                }
            }
            case ADJUST_PRICE -> {
                BigDecimal percent = request.percent();
                if (percent == null) throw new IllegalArgumentException("percent is required");
                if (percent.compareTo(new BigDecimal("-90")) < 0 || percent.compareTo(new BigDecimal("500")) > 0) {
                    throw new IllegalArgumentException("percent must be between -90 and 500");
                }
                Set<UUID> excluded = request.excludedVariantIds() == null ? Set.of() : new HashSet<>(request.excludedVariantIds());
                BigDecimal factor = BigDecimal.ONE.add(percent.movePointLeft(2));
                List<UUID> productIds = products.stream().map(Product::getId).toList();
                for (ProductVariant v : variantRepository.findByProductIdInAndDeletedFalse(productIds)) {
                    if (excluded.contains(v.getId())) continue;
                    if (v.getSellingPrice() == null) {
                        warnings.add(v.getSku() + ": has no price yet, skipped");
                        continue;
                    }
                    BigDecimal newPrice = v.getSellingPrice().multiply(factor).setScale(0, RoundingMode.HALF_UP);
                    if (newPrice.signum() < 0) newPrice = BigDecimal.ZERO;
                    v.setSellingPrice(newPrice);
                    if (v.getDealPrice() != null && v.getDealPrice().compareTo(newPrice) >= 0) {
                        v.setDealPrice(null);
                        v.setDealEndsAt(null);
                        warnings.add(v.getSku() + ": its sale price was no longer below the new price, so the sale was cleared");
                    }
                    variantRepository.save(v);
                    affectedVariants++;
                }
            }
        }

        return new BulkProductActionResult(products.size(), affectedVariants, warnings);
    }

    private List<Category> resolveCategories(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Category> result = new ArrayList<>();
        for (UUID id : ids) {
            result.add(categoryRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("No category: " + id)));
        }
        return result;
    }

    @Transactional
    public ProductVariantDto addVariant(UUID productId, CreateProductRequest.CreateVariantRequest vr) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        return createVariant(product, vr);
    }

    /** Partial update — {@code sku} isn't editable, see {@link UpdateVariantRequest}'s javadoc. */
    @Transactional
    public ProductVariantDto updateVariant(UUID productId, UUID variantId, UpdateVariantRequest request) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("No variant: " + variantId));
        if (!variant.getProduct().getId().equals(productId)) {
            throw new EntityNotFoundException("No variant " + variantId + " on product " + productId);
        }

        if (request.variantName() != null) variant.setVariantName(request.variantName());
        if (request.sellingPrice() != null) variant.setSellingPrice(new BigDecimal(request.sellingPrice()));
        if (request.compareAtPrice() != null) variant.setCompareAtPrice(new BigDecimal(request.compareAtPrice()));
        if (request.standardCost() != null) variant.setStandardCost(new BigDecimal(request.standardCost()));
        if (request.uom() != null) variant.setUom(request.uom());
        if (request.vatRate() != null) variant.setVatRate(new BigDecimal(request.vatRate()));
        if (request.vatExempt() != null) variant.setVatExempt(request.vatExempt());
        if (request.reorderLevel() != null) variant.setReorderLevel(new BigDecimal(request.reorderLevel()));
        if (request.isActive() != null) variant.setActive(request.isActive());

        variant = variantRepository.save(variant);
        return ProductVariantDto.from(variant, null, attributesFor(List.of(variant.getId())));
    }

    private ProductVariantDto createVariant(Product product, CreateProductRequest.CreateVariantRequest vr) {
        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku(vr.sku() != null && !vr.sku().isBlank() ? vr.sku().trim() : generateSku(product));
        variant.setVariantName(vr.variantName());
        variant.setSellingPrice(new BigDecimal(vr.sellingPrice()));
        if (vr.compareAtPrice() != null) variant.setCompareAtPrice(new BigDecimal(vr.compareAtPrice()));
        if (vr.standardCost() != null) variant.setStandardCost(new BigDecimal(vr.standardCost()));
        if (vr.uom() != null) variant.setUom(vr.uom());
        if (vr.vatRate() != null) variant.setVatRate(new BigDecimal(vr.vatRate()));
        variant.setVatExempt(vr.vatExempt());
        if (vr.reorderLevel() != null) variant.setReorderLevel(new BigDecimal(vr.reorderLevel()));

        variant = variantRepository.save(variant);

        if (vr.attributeValueIds() != null) {
            for (UUID attrValueId : vr.attributeValueIds()) {
                ProductAttributeValue attrValue = attributeValueRepository.findById(attrValueId)
                        .orElseThrow(() -> new EntityNotFoundException("No attribute value: " + attrValueId));
                VariantAttributeValue join = new VariantAttributeValue();
                join.setVariant(variant);
                join.setAttributeValue(attrValue);
                variantAttributeValueRepository.save(join);
            }
        }

        if (vr.barcodes() != null) {
            for (String code : vr.barcodes()) {
                VariantBarcode barcode = new VariantBarcode();
                barcode.setVariant(variant);
                barcode.setBarcode(code);
                barcode.setBarcodeType(VariantBarcode.BarcodeType.INTERNAL);
                barcode.setPrimary(variantBarcodeRepository.findByVariantId(variant.getId()).isEmpty());
                variantBarcodeRepository.save(barcode);
            }
        }

        return ProductVariantDto.from(variant, null, attributesFor(List.of(variant.getId())));
    }

    /**
     * Category-prefixed, sequential, auto-generated when a caller doesn't supply an explicit SKU
     * (see {@link CreateProductRequest.CreateVariantRequest#sku}'s javadoc). Prefers the more
     * specific subcategory over the category so e.g. "Gel polish" under "Nails" gets its own
     * prefix rather than sharing NAIL- with every other nail product.
     */
    private String generateSku(Product product) {
        Category prefixSource = product.getCategories().isEmpty() ? null : product.getCategories().get(0);
        String prefix = prefixSource == null ? "SKU" : categoryPrefix(prefixSource.getSlug());
        return prefix + "-" + String.format("%06d", variantRepository.nextSkuSequence());
    }

    private String categoryPrefix(String slug) {
        String letters = slug.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return letters.isBlank() ? "SKU" : letters.substring(0, Math.min(4, letters.length()));
    }

    @Transactional(readOnly = true)
    public ProductDto getProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + id));
        return ProductDto.from(product, variantDtos(id), primaryImageUrl(id));
    }

    private String primaryImageUrl(UUID productId) {
        List<ProductImage> images = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        return images.stream().filter(ProductImage::isPrimary).findFirst()
                .or(images.stream()::findFirst)
                .map(ProductImage::getUrl)
                .orElse(null);
    }

    /**
     * Audit finding F12 (session 3): this had no pagination and issued one
     * variant query per product — fine at a handful of products, a 1000-query
     * response against the founder's stated 1000+ SKU catalogue. Now paginated,
     * with variants for the whole page batch-fetched in a single query.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ProductDto> listProducts(
            String q, UUID categoryId, Boolean published, String stock, Boolean popular, Boolean newArrival,
            boolean deleted, org.springframework.data.domain.Pageable pageable) {
        String stockFilter = stock == null || stock.isBlank() ? null : stock.trim().toUpperCase(Locale.ROOT);
        if (stockFilter != null && !stockFilter.equals("IN") && !stockFilter.equals("OUT")) {
            throw new IllegalArgumentException("stock must be IN or OUT");
        }
        String query = q == null || q.isBlank() ? null : q.trim();
        var page = productRepository.searchAdmin(query, categoryId, published, stockFilter, popular, newArrival, deleted, pageable);
        List<UUID> productIds = page.getContent().stream().map(Product::getId).toList();

        List<ProductVariant> allVariants = variantRepository.findByProductIdInAndDeletedFalse(productIds);
        List<UUID> allVariantIds = allVariants.stream().map(ProductVariant::getId).toList();
        Map<UUID, BigDecimal> available = availability(allVariantIds);
        List<VariantAttributeValue> allAttrs = attributesFor(allVariantIds);
        Map<UUID, List<ProductVariantDto>> variantsByProduct = allVariants.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        v -> v.getProduct().getId(),
                        java.util.stream.Collectors.mapping(
                                v -> ProductVariantDto.from(v, available.getOrDefault(v.getId(), BigDecimal.ZERO), allAttrs),
                                java.util.stream.Collectors.toList())));

        Map<UUID, String> primaryImageByProduct = new HashMap<>();
        for (ProductImage image : imageRepository.findByProductIdInOrderBySortOrderAsc(productIds)) {
            UUID pid = image.getProduct().getId();
            if (!primaryImageByProduct.containsKey(pid) || image.isPrimary()) {
                primaryImageByProduct.put(pid, image.getUrl());
            }
        }

        return page.map(p -> ProductDto.from(p, variantsByProduct.getOrDefault(p.getId(), List.of()),
                primaryImageByProduct.get(p.getId())));
    }

    /**
     * Soft delete: the product leaves the storefront and the default staff list but keeps its history
     * (orders and stock movements still point at its variants). It is also unpublished so a restore
     * never silently republishes it.
     */
    @Transactional
    public ProductDto softDelete(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        product.setDeleted(true);
        product.setPublished(false);
        product.setPublishedAt(null);
        productRepository.save(product);
        return ProductDto.from(product, variantDtos(productId), primaryImageUrl(productId));
    }

    @Transactional
    public ProductDto restore(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        product.setDeleted(false);
        productRepository.save(product);
        return ProductDto.from(product, variantDtos(productId), primaryImageUrl(productId));
    }

    /**
     * Products are created unpublished (see {@link #createProduct}'s comment) —
     * this is the explicit "go live" action staff take once photos/pricing are
     * ready, matching what the architecture doc describes for the storefront.
     */
    @Transactional
    public ProductDto setPublished(UUID productId, boolean published) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        product.setPublished(published);
        product.setPublishedAt(published ? Instant.now() : null);
        productRepository.save(product);
        return ProductDto.from(product, variantDtos(productId), primaryImageUrl(productId));
    }

    /** A product's gallery as staff see it: the primary image first, the rest in the order they were added. */
    @Transactional(readOnly = true)
    public List<CatalogImageDto> listImages(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw new EntityNotFoundException("No product: " + productId);
        }
        return imageRepository.findByProductIdOrderBySortOrderAsc(productId).stream()
                .sorted(java.util.Comparator.comparing((ProductImage i) -> !i.isPrimary()))
                .map(CatalogImageDto::from)
                .toList();
    }

    @Transactional
    public CatalogImageDto attachImage(UUID productId, AttachImageRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        List<ProductImage> existing = imageRepository.findByProductIdOrderBySortOrderAsc(productId);

        ProductImage image = new ProductImage();
        image.setProduct(product);
        if (request.variantId() != null) {
            image.setVariant(variantRepository.findById(request.variantId())
                    .orElseThrow(() -> new EntityNotFoundException("No variant: " + request.variantId())));
        }
        image.setUrl(request.url());
        image.setThumbnailUrl(request.thumbnailUrl());
        image.setAltText(request.altText());
        image.setWidth(request.width());
        image.setHeight(request.height());
        image.setByteSize(request.byteSize());
        // Append after the current last image (max + 1, not size: removals leave gaps).
        image.setSortOrder(existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1);

        // A product's first image is always its primary one, whatever the request says;
        // otherwise the storefront list would show no photo although the gallery has one.
        boolean makePrimary = request.isPrimary() || existing.isEmpty();
        if (makePrimary) {
            demoteAll(existing);
        }
        image.setPrimary(makePrimary);

        return CatalogImageDto.from(imageRepository.save(image));
    }

    /** Makes an existing image the product's primary one; the previous primary is demoted. */
    @Transactional
    public CatalogImageDto setPrimaryImage(UUID productId, UUID imageId) {
        List<ProductImage> images = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        ProductImage target = images.stream()
                .filter(i -> i.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("No image " + imageId + " on product " + productId));
        demoteAll(images);
        target.setPrimary(true);
        return CatalogImageDto.from(imageRepository.save(target));
    }

    @Transactional
    public void removeImage(UUID productId, UUID imageId) {
        List<ProductImage> images = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        ProductImage removed = images.stream()
                .filter(i -> i.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("No image " + imageId + " on product " + productId));
        imageRepository.delete(removed);

        // Removing the primary must not leave a gallery with no primary: promote the next one.
        if (removed.isPrimary()) {
            images.stream()
                    .filter(i -> !i.getId().equals(imageId))
                    .findFirst()
                    .ifPresent(next -> {
                        next.setPrimary(true);
                        imageRepository.save(next);
                    });
        }
    }

    private void demoteAll(List<ProductImage> images) {
        images.stream().filter(ProductImage::isPrimary).forEach(i -> {
            i.setPrimary(false);
            imageRepository.save(i);
        });
    }

    private String generateUniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "product";
        }
        String candidate = base;
        while (productRepository.existsBySlug(candidate)) {
            candidate = base + "-" + (1000 + RANDOM.nextInt(9000));
        }
        return candidate;
    }
}
