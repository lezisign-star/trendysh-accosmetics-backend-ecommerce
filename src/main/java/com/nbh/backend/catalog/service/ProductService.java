package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.dto.BulkProductActionRequest;
import com.nbh.backend.catalog.dto.BulkProductActionResult;
import com.nbh.backend.catalog.dto.ProductDto;
import com.nbh.backend.catalog.dto.ProductVariantDto;
import com.nbh.backend.catalog.dto.SaveProductRequest;
import com.nbh.backend.catalog.entity.*;
import com.nbh.backend.catalog.repository.*;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.service.StockAvailabilityService;
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

    /** Field-injected (optional) so the many hand-built test instances keep working; null just means "no live push". */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher events;

    /** Takes deleted photos off Cloudinary after the removal commits; same optional injection, null = nothing to clean. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ImageAssetCleaner assetCleaner;

    /** The AI photos waiting for approval, so a removed option can take its own with it; same optional injection. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.imagecleanup.ImageDraftRepository drafts;

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository imageRepository;
    private final StockAvailabilityService availabilityService;

    public ProductService(ProductRepository productRepository,
                           ProductVariantRepository variantRepository,
                           BrandRepository brandRepository,
                           CategoryRepository categoryRepository,
                           ProductImageRepository imageRepository,
                           StockAvailabilityService availabilityService) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.brandRepository = brandRepository;
        this.categoryRepository = categoryRepository;
        this.imageRepository = imageRepository;
        this.availabilityService = availabilityService;
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

    /** A product's live (non-deleted) variants with their sellable stock filled in. */
    private List<ProductVariantDto> variantDtos(UUID productId) {
        List<ProductVariant> variants = variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        List<UUID> variantIds = variants.stream().map(ProductVariant::getId).toList();
        // Phase 0 fix (2026-09-27): this used to reimplement StockAvailabilityService.available
        // verbatim against the repository directly — the exact read-side bypass its own javadoc
        // claims doesn't happen. Delegates now; behaviour is unchanged (same repository query).
        Map<UUID, BigDecimal> available = availabilityService.available(variantIds);
        return variants.stream()
                .map(v -> ProductVariantDto.from(v, available.getOrDefault(v.getId(), BigDecimal.ZERO)))
                .toList();
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
        List<ProductVariant> kept = new ArrayList<>();

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
            kept.add(variant);
        }

        for (ProductVariant v : existing) {
            if (!keep.contains(v.getId())) {
                v.setDeleted(true);
                v.setActive(false);
                variantRepository.save(v);
                removePhotosOf(v);
            }
        }

        if (kept.size() == 1) {
            fileLoosePhotosUnder(product, kept.get(0));
        }
    }

    /**
     * A product that is down to one option: the photos that were not given to any option while there were several
     * belong to that one now. The shop already shows them on it (see {@link PhotoOwnership}), but the editor lists an
     * option's own photos only, so left at product level staff could not reorder or remove them. They are filed under
     * the option, keeping one main photo (the option's own if it has one, else the first of these).
     */
    private void fileLoosePhotosUnder(Product product, ProductVariant sole) {
        List<ProductImage> images = imageRepository.findByProductIdOrderBySortOrderAsc(product.getId());
        List<ProductImage> loose = PhotoOwnership.sameOwner(images, null);
        if (loose.isEmpty()) {
            return;
        }
        boolean hasMain = PhotoOwnership.sameOwner(images, sole.getId()).stream().anyMatch(ProductImage::isPrimary);
        UUID newMain = hasMain ? null
                : loose.stream().filter(ProductImage::isPrimary).findFirst().orElse(loose.get(0)).getId();
        for (ProductImage photo : loose) {
            photo.setVariant(sole);
            photo.setPrimary(photo.getId().equals(newMain));
        }
        imageRepository.saveAll(loose);
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

        Map<UUID, ProductVariant> copiedOption = new HashMap<>();
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
            copiedOption.put(sv.getId(), nv);
        }

        for (ProductImage si : imageRepository.findByProductIdOrderBySortOrderAsc(productId)) {
            // A photo stays with the same option in the copy; the copy shares the Cloudinary file until either is
            // removed (a file is only deleted from Cloudinary once no photo row uses it any more).
            UUID ownerId = PhotoOwnership.ownerOf(si);
            ProductVariant copiedOwner = ownerId == null ? null : copiedOption.get(ownerId);
            if (ownerId != null && copiedOwner == null) {
                continue; // it belonged to an option that has since been removed
            }
            ProductImage ni = new ProductImage();
            ni.setProduct(copy);
            ni.setVariant(copiedOwner);
            ni.setSource(si.getSource());
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
                    if (v.getWholesalePrice() != null && v.getWholesalePrice().compareTo(newPrice) >= 0) {
                        v.setWholesaleMinQty(null);
                        v.setWholesalePrice(null);
                        warnings.add(v.getSku() + ": its wholesale price was no longer below the new price, so the wholesale tier was cleared");
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

    /**
     * Category-prefixed, sequential, auto-generated for every new variant — SKUs are never
     * user-editable (see {@link #saveVariants}). Prefers the more specific subcategory over the
     * category so e.g. "Gel polish" under "Nails" gets its own prefix rather than sharing NAIL-
     * with every other nail product.
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

    /** The photo that stands for the product in a staff list: see {@link PhotoOwnership#productCover}. */
    private String primaryImageUrl(UUID productId) {
        List<ProductVariant> options = variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        List<ProductImage> images = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        return PhotoOwnership.productCover(options.stream().map(ProductVariant::getId).toList(), options.size() <= 1, images)
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
        Map<UUID, BigDecimal> available = availabilityService.available(allVariantIds);
        Map<UUID, List<ProductVariantDto>> variantsByProduct = allVariants.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        v -> v.getProduct().getId(),
                        java.util.stream.Collectors.mapping(
                                v -> ProductVariantDto.from(v, available.getOrDefault(v.getId(), BigDecimal.ZERO)),
                                java.util.stream.Collectors.toList())));

        Map<UUID, List<ProductImage>> imagesByProduct = imageRepository.findByProductIdInOrderBySortOrderAsc(productIds)
                .stream().collect(java.util.stream.Collectors.groupingBy(i -> i.getProduct().getId()));
        Map<UUID, List<UUID>> optionIdsByProduct = allVariants.stream()
                .sorted(java.util.Comparator.comparingInt(ProductVariant::getSortOrder))
                .collect(java.util.stream.Collectors.groupingBy(v -> v.getProduct().getId(),
                        java.util.stream.Collectors.mapping(ProductVariant::getId, java.util.stream.Collectors.toList())));

        Map<UUID, Long> pendingDrafts = drafts == null || productIds.isEmpty() ? Map.of()
                : drafts.countByProducts(productIds).stream().collect(java.util.stream.Collectors.toMap(
                        com.nbh.backend.imagecleanup.ImageDraftRepository.DraftCount::getProductId,
                        com.nbh.backend.imagecleanup.ImageDraftRepository.DraftCount::getDrafts));

        return page.map(p -> {
            List<UUID> optionIds = optionIdsByProduct.getOrDefault(p.getId(), List.of());
            List<ProductImage> photos = imagesByProduct.getOrDefault(p.getId(), List.of());
            String cover = PhotoOwnership.productCover(optionIds, optionIds.size() <= 1, photos)
                    .map(ProductImage::getUrl).orElse(null);
            return ProductDto.from(p, variantsByProduct.getOrDefault(p.getId(), List.of()), cover)
                    .withPhotoStats(photoStats(optionIds, photos, pendingDrafts.getOrDefault(p.getId(), 0L)));
        });
    }

    /**
     * A product's photos for the staff list, grouped by option in the product's own order (each option's main photo
     * first, photos with no option last). A removed option's photos are not counted.
     */
    private static ProductDto.PhotoStats photoStats(List<UUID> optionIds, List<ProductImage> photos, long pendingDrafts) {
        java.util.function.ToIntFunction<ProductImage> optionRank = i -> {
            UUID owner = PhotoOwnership.ownerOf(i);
            return owner == null ? Integer.MAX_VALUE : optionIds.indexOf(owner);
        };
        List<ProductImage> live = photos.stream()
                .filter(i -> PhotoOwnership.ownerOf(i) == null || optionIds.contains(PhotoOwnership.ownerOf(i)))
                .sorted(java.util.Comparator.comparingInt(optionRank)
                        .thenComparing(java.util.Comparator.comparing(ProductImage::isPrimary).reversed())
                        .thenComparingInt(ProductImage::getSortOrder))
                .toList();
        int ai = (int) live.stream().filter(i -> i.getSource() == ProductImage.ImageSource.AI_CLEANED).count();
        // On a product with one option a photo still filed at product level is that option's own, not "unassigned".
        int unassigned = optionIds.size() > 1 ? (int) live.stream().filter(i -> PhotoOwnership.ownerOf(i) == null).count() : 0;
        List<ProductDto.PhotoSummary> listed = live.stream().limit(ProductDto.PhotoStats.MAX_LISTED)
                .map(i -> new ProductDto.PhotoSummary(i.getId(), i.getUrl(), i.getThumbnailUrl(), PhotoOwnership.ownerOf(i),
                        i.isPrimary(), i.getSource() == ProductImage.ImageSource.AI_CLEANED))
                .toList();
        return new ProductDto.PhotoStats(listed, live.size(), ai, unassigned, (int) pendingDrafts);
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
     *
     * Uses a targeted update rather than loading the entity and saving it, so this does
     * NOT bump {@code updatedAt} — found live (2026-09-29): it used to go through the
     * normal managed-entity save, and a staff member who published a fresh draft and then
     * saved the same open editor shortly after could get a spurious "Someone else changed
     * this product" (see {@link #saveProduct}'s expectedUpdatedAt check) whenever the two
     * requests' commits happened to land in different wall-clock seconds — a real gap of
     * well under a second, but enough to trip the truncated-to-seconds comparison.
     * Publishing is a narrow, idempotent toggle, not a conflicting edit to the editor's own
     * fields, so it should not be able to invalidate the editor's optimistic-lock token.
     */
    @Transactional
    public ProductDto setPublished(UUID productId, boolean published) {
        if (!productRepository.existsById(productId)) {
            throw new EntityNotFoundException("No product: " + productId);
        }
        productRepository.updatePublishedState(productId, published, published ? Instant.now() : null);
        // The bulk UPDATE above bypasses entity lifecycle callbacks, so tell live listeners explicitly.
        if (events != null) {
            events.publishEvent(new com.nbh.backend.common.event.DataChangedEvent(
                    com.nbh.backend.common.event.DataChangedEvent.Topic.PRODUCTS));
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        return ProductDto.from(product, variantDtos(productId), primaryImageUrl(productId));
    }

    /**
     * A product's photos as staff see them: every live option's, each carrying its owner ({@code variantId}; null =
     * not assigned to an option), main photos first. A removed option's photos are gone with it.
     */
    @Transactional(readOnly = true)
    public List<AdminImageDto> listImages(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw new EntityNotFoundException("No product: " + productId);
        }
        Set<UUID> liveOptions = variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId).stream()
                .map(ProductVariant::getId).collect(Collectors.toSet());
        return imageRepository.findByProductIdOrderBySortOrderAsc(productId).stream()
                .filter(i -> PhotoOwnership.ownerOf(i) == null || liveOptions.contains(PhotoOwnership.ownerOf(i)))
                .sorted(java.util.Comparator.comparing((ProductImage i) -> !i.isPrimary()))
                .map(AdminImageDto::from)
                .toList();
    }

    /**
     * Files a new photo under one option. A product with a single option files it under that option without being
     * told; with several, the caller must say which (owner, 2026-10-07: a photo belongs to exactly one option, there
     * are no shared "whole product" photos). An option's first photo is always its main one, whatever the request says;
     * otherwise its card would show no photo although it has some.
     */
    @Transactional
    public AdminImageDto attachImage(UUID productId, AttachImageRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        ProductVariant owner = ownerForNewPhoto(productId, request.variantId());
        List<ProductImage> existing = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        List<ProductImage> mine = PhotoOwnership.sameOwner(existing, owner == null ? null : owner.getId());

        ProductImage image = new ProductImage();
        image.setProduct(product);
        image.setVariant(owner);
        image.setUrl(request.url());
        image.setThumbnailUrl(request.thumbnailUrl());
        image.setAltText(request.altText());
        image.setWidth(request.width());
        image.setHeight(request.height());
        image.setByteSize(request.byteSize());
        // Append after the current last image (max + 1, not size: removals leave gaps).
        image.setSortOrder(existing.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1);

        boolean makeMain = request.isPrimary() || mine.isEmpty();
        if (makeMain) {
            demoteMain(mine);
        }
        image.setPrimary(makeMain);

        return AdminImageDto.from(imageRepository.save(image));
    }

    /** The option a new photo goes to; see {@link #attachImage}. Null only for a bare product that has no option yet. */
    private ProductVariant ownerForNewPhoto(UUID productId, UUID requestedVariantId) {
        List<ProductVariant> options = variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        if (requestedVariantId != null) {
            return options.stream().filter(v -> v.getId().equals(requestedVariantId)).findFirst()
                    .orElseThrow(() -> new UnprocessableException("That option doesn't belong to this product."));
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        if (options.size() > 1) {
            throw new UnprocessableException("This product has several options, so say which option the photo is for.");
        }
        return null;
    }

    /** Makes a photo its option's main one. That option's previous main photo is demoted; other options are untouched. */
    @Transactional
    public AdminImageDto setPrimaryImage(UUID productId, UUID imageId) {
        List<ProductImage> images = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        ProductImage target = images.stream()
                .filter(i -> i.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("No image " + imageId + " on product " + productId));
        demoteMain(PhotoOwnership.sameOwner(images, PhotoOwnership.ownerOf(target)));
        target.setPrimary(true);
        return AdminImageDto.from(imageRepository.save(target));
    }

    /**
     * Removes a photo from its option and, once that has committed, from Cloudinary too (unless another photo row
     * still uses the same file). If it was the option's main photo the option's next one takes over.
     */
    @Transactional
    public void removeImage(UUID productId, UUID imageId) {
        List<ProductImage> images = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        ProductImage removed = images.stream()
                .filter(i -> i.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("No image " + imageId + " on product " + productId));
        imageRepository.delete(removed);

        // Removing the main photo must not leave an option with photos but no main one: promote its next.
        if (removed.isPrimary()) {
            PhotoOwnership.sameOwner(images, PhotoOwnership.ownerOf(removed)).stream()
                    .filter(i -> !i.getId().equals(imageId))
                    .findFirst()
                    .ifPresent(next -> {
                        next.setPrimary(true);
                        imageRepository.save(next);
                    });
        }
        releaseFromCloudinary(List.of(removed.getUrl()));
    }

    /**
     * Gives a photo to one option: how a photo that was filed at product level (before the one-owner rule) gets an
     * owner, and how a photo put on the wrong option is moved. Lands at the end of that option's gallery; it is the
     * option's main photo only if the option had none. If it was main where it was, its old owner's next photo takes over.
     */
    @Transactional
    public AdminImageDto assignImage(UUID productId, UUID imageId, UUID variantId) {
        List<ProductImage> images = imageRepository.findByProductIdOrderBySortOrderAsc(productId);
        ProductImage image = images.stream()
                .filter(i -> i.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("No image " + imageId + " on product " + productId));
        ProductVariant target = variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId).stream()
                .filter(v -> v.getId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new UnprocessableException("That option doesn't belong to this product."));
        UUID oldOwner = PhotoOwnership.ownerOf(image);
        if (target.getId().equals(oldOwner)) {
            return AdminImageDto.from(image);
        }

        List<ProductImage> oldNeighbours = PhotoOwnership.sameOwner(images, oldOwner).stream()
                .filter(i -> !i.getId().equals(imageId)).toList();
        boolean wasMain = image.isPrimary();
        boolean optionHadPhotos = !PhotoOwnership.sameOwner(images, target.getId()).isEmpty();

        image.setVariant(target);
        image.setSortOrder(images.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1);
        image.setPrimary(!optionHadPhotos);
        imageRepository.save(image);

        if (wasMain && !oldNeighbours.isEmpty() && oldNeighbours.stream().noneMatch(ProductImage::isPrimary)) {
            ProductImage next = oldNeighbours.get(0);
            next.setPrimary(true);
            imageRepository.save(next);
        }
        return AdminImageDto.from(image);
    }

    private void demoteMain(List<ProductImage> sameOwnerPhotos) {
        sameOwnerPhotos.stream().filter(ProductImage::isPrimary).forEach(i -> {
            i.setPrimary(false);
            imageRepository.save(i);
        });
    }

    private void releaseFromCloudinary(List<String> urls) {
        if (assetCleaner != null) {
            assetCleaner.releaseAfterCommit(urls);
        }
    }

    /**
     * A removed option takes its photos with it, and the AI photos waiting for approval for it: they belong to nothing
     * else, and left behind they would sit on Cloudinary for good.
     */
    private void removePhotosOf(ProductVariant option) {
        List<ProductImage> own = imageRepository.findByVariantIdOrderBySortOrderAsc(option.getId());
        List<com.nbh.backend.imagecleanup.ImageDraft> waiting =
                drafts == null ? List.of() : drafts.findByVariantIdOrderByCreatedAtDesc(option.getId());
        if (own.isEmpty() && waiting.isEmpty()) {
            return;
        }
        List<String> urls = new ArrayList<>();
        own.forEach(i -> urls.add(i.getUrl()));
        waiting.forEach(d -> urls.add(d.getUrl()));
        imageRepository.deleteAll(own);
        if (!waiting.isEmpty()) {
            drafts.deleteAll(waiting);
        }
        releaseFromCloudinary(urls);
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
