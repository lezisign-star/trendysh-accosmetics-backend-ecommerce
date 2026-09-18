package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.dto.CatalogImageDto;
import com.nbh.backend.catalog.dto.CreateProductRequest;
import com.nbh.backend.catalog.dto.ProductDto;
import com.nbh.backend.catalog.dto.ProductVariantDto;
import com.nbh.backend.catalog.dto.UpdateProductRequest;
import com.nbh.backend.catalog.dto.UpdateVariantRequest;
import com.nbh.backend.catalog.entity.*;
import com.nbh.backend.catalog.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

    public ProductService(ProductRepository productRepository,
                           ProductVariantRepository variantRepository,
                           BrandRepository brandRepository,
                           CategoryRepository categoryRepository,
                           ProductAttributeValueRepository attributeValueRepository,
                           VariantAttributeValueRepository variantAttributeValueRepository,
                           VariantBarcodeRepository variantBarcodeRepository,
                           ProductImageRepository imageRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.brandRepository = brandRepository;
        this.categoryRepository = categoryRepository;
        this.attributeValueRepository = attributeValueRepository;
        this.variantAttributeValueRepository = variantAttributeValueRepository;
        this.variantBarcodeRepository = variantBarcodeRepository;
        this.imageRepository = imageRepository;
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

        if (request.brandId() != null) {
            product.setBrand(brandRepository.findById(request.brandId())
                    .orElseThrow(() -> new EntityNotFoundException("No brand: " + request.brandId())));
        }
        if (request.categoryId() != null) {
            product.setCategory(categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new EntityNotFoundException("No category: " + request.categoryId())));
        }
        if (request.subcategoryId() != null) {
            product.setSubcategory(categoryRepository.findById(request.subcategoryId())
                    .orElseThrow(() -> new EntityNotFoundException("No subcategory: " + request.subcategoryId())));
        }

        Product savedProduct = productRepository.save(product);

        List<ProductVariantDto> variantDtos = request.variants().stream()
                .map(vr -> createVariant(savedProduct, vr))
                .toList();

        return ProductDto.from(savedProduct, variantDtos);
    }

    /**
     * Full-replace update of a product's own fields. Founder's explicit ask
     * ("add a variant under a product") surfaced that this — and {@link
     * #addVariant} below — didn't exist at all: the original API could only
     * create a product with its initial variants in one shot, with no way
     * to correct a typo or add a shade discovered after the fact.
     */
    @Transactional
    public ProductDto updateProduct(UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));

        product.setName(request.name());
        product.setShortDescription(request.shortDescription());
        product.setDescription(request.description());
        product.setIngredients(request.ingredients());
        product.setHowToUse(request.howToUse());
        product.setBrand(request.brandId() != null
                ? brandRepository.findById(request.brandId())
                        .orElseThrow(() -> new EntityNotFoundException("No brand: " + request.brandId()))
                : null);
        product.setCategory(request.categoryId() != null
                ? categoryRepository.findById(request.categoryId())
                        .orElseThrow(() -> new EntityNotFoundException("No category: " + request.categoryId()))
                : null);
        product.setSubcategory(request.subcategoryId() != null
                ? categoryRepository.findById(request.subcategoryId())
                        .orElseThrow(() -> new EntityNotFoundException("No subcategory: " + request.subcategoryId()))
                : null);

        productRepository.save(product);
        List<ProductVariantDto> variants = variantRepository.findByProductId(productId).stream()
                .map(ProductVariantDto::from)
                .toList();
        return ProductDto.from(product, variants, primaryImageUrl(productId));
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

        return ProductVariantDto.from(variantRepository.save(variant));
    }

    private ProductVariantDto createVariant(Product product, CreateProductRequest.CreateVariantRequest vr) {
        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku(vr.sku());
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

        return ProductVariantDto.from(variant);
    }

    @Transactional(readOnly = true)
    public ProductDto getProduct(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + id));
        List<ProductVariantDto> variants = variantRepository.findByProductId(id).stream()
                .map(ProductVariantDto::from)
                .toList();
        return ProductDto.from(product, variants, primaryImageUrl(id));
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
    public org.springframework.data.domain.Page<ProductDto> listProducts(org.springframework.data.domain.Pageable pageable) {
        var page = productRepository.findAll(pageable);
        List<UUID> productIds = page.getContent().stream().map(Product::getId).toList();

        Map<UUID, List<ProductVariantDto>> variantsByProduct = variantRepository.findByProductIdIn(productIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        v -> v.getProduct().getId(),
                        java.util.stream.Collectors.mapping(ProductVariantDto::from, java.util.stream.Collectors.toList())));

        Map<UUID, String> primaryImageByProduct = new java.util.HashMap<>();
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
        List<ProductVariantDto> variants = variantRepository.findByProductId(productId).stream()
                .map(ProductVariantDto::from)
                .toList();
        return ProductDto.from(product, variants);
    }

    @Transactional
    public CatalogImageDto attachImage(UUID productId, AttachImageRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));

        ProductImage image = new ProductImage();
        image.setProduct(product);
        if (request.variantId() != null) {
            image.setVariant(variantRepository.findById(request.variantId())
                    .orElseThrow(() -> new EntityNotFoundException("No variant: " + request.variantId())));
        }
        image.setUrl(request.url());
        image.setThumbnailUrl(request.thumbnailUrl());
        image.setAltText(request.altText());
        image.setPrimary(request.isPrimary());
        image.setWidth(request.width());
        image.setHeight(request.height());
        image.setByteSize(request.byteSize());
        image.setSortOrder(imageRepository.findByProductIdOrderBySortOrderAsc(productId).size());

        if (request.isPrimary()) {
            // Only one primary image per product — demote any existing one rather
            // than leaving two rows both claiming to be it.
            imageRepository.findByProductIdOrderBySortOrderAsc(productId).forEach(existing -> {
                if (existing.isPrimary()) {
                    existing.setPrimary(false);
                    imageRepository.save(existing);
                }
            });
        }

        return CatalogImageDto.from(imageRepository.save(image));
    }

    @Transactional
    public void removeImage(UUID productId, UUID imageId) {
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new EntityNotFoundException("No image: " + imageId));
        if (!image.getProduct().getId().equals(productId)) {
            throw new EntityNotFoundException("No image " + imageId + " on product " + productId);
        }
        imageRepository.delete(image);
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
