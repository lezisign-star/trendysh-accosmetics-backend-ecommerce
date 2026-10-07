package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.MockDataStatusDto;
import com.nbh.backend.catalog.dto.SaveProductRequest;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductReview;
import com.nbh.backend.catalog.entity.ProductSaleEvent;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductReviewRepository;
import com.nbh.backend.catalog.repository.ProductSaleEventRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.kits.dto.KitDtos.ComponentInput;
import com.nbh.backend.kits.dto.KitDtos.SaveKitRequest;
import com.nbh.backend.kits.dto.ProfessionDtos.ProfessionRequest;
import com.nbh.backend.kits.entity.Profession;
import com.nbh.backend.kits.repository.ProfessionRepository;
import com.nbh.backend.kits.service.KitService;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.service.VariantStockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Developer-toggleable demo content (2026-09-29) — lets the developer show the client a
 * realistically-populated catalogue without touching the client's own real data. See the plan this
 * was built from: {@code one-click-add-kit-to-glistening-pinwheel.md} in the developer's local
 * Claude plans folder, written 2026-09-29, for the full design reasoning.
 *
 * <p>Every row this creates is tagged {@code isMockData = true} on {@link Product}/{@link Category}/
 * {@link Profession}/{@link CustomerOrder}. Toggling is then just a bulk flip of
 * {@code isPublished} on the tagged product/category rows — reuses the exact same "published"
 * mechanism every storefront query already filters on, so no other query in the codebase needed to
 * change. Kits and professions piggyback on their backing product's/kits' visibility for free (see
 * {@link KitService#listPublicKits()}); a profession with zero currently-visible kits already drops
 * out of the public grouping on its own.
 *
 * <p>Reuses the same already-validated service methods the real admin UI uses to create things
 * ({@link ProductService#saveProduct}, {@link VariantStockService#setOnHand},
 * {@link KitService#create}) rather than hand-rolling inserts, so SKU generation, stock-ledger
 * correctness, and validation all come for free. The one thing those don't cover — fake
 * reviews/units-sold, which both require a backing {@link CustomerOrder} row via a NOT NULL FK —
 * is built directly via repository saves, deliberately NOT through the real checkout flow (which
 * would trigger real side effects: TumaBoda API calls, email sends, stock reservations that have no
 * business firing for synthetic data).
 */
@Service
public class MockDataSeederService {

    private final Random random = new Random();

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository imageRepository;
    private final VariantStockService stockService;
    private final KitService kitService;
    private final ProfessionRepository professionRepository;
    private final CustomerOrderRepository orderRepository;
    private final ProductReviewRepository reviewRepository;
    private final ProductSaleEventRepository saleEventRepository;

    public MockDataSeederService(ProductService productService, ProductRepository productRepository,
                                  CategoryRepository categoryRepository, ProductVariantRepository variantRepository,
                                  ProductImageRepository imageRepository, VariantStockService stockService,
                                  KitService kitService, ProfessionRepository professionRepository,
                                  CustomerOrderRepository orderRepository, ProductReviewRepository reviewRepository,
                                  ProductSaleEventRepository saleEventRepository) {
        this.productService = productService;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.variantRepository = variantRepository;
        this.imageRepository = imageRepository;
        this.stockService = stockService;
        this.kitService = kitService;
        this.professionRepository = professionRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
        this.saleEventRepository = saleEventRepository;
    }

    @Transactional(readOnly = true)
    public MockDataStatusDto status() {
        List<Product> mockProducts = productRepository.findAll().stream().filter(Product::isMockData).toList();
        boolean enabled = mockProducts.stream().anyMatch(Product::isPublished);
        Instant generatedAt = mockProducts.stream().map(Product::getCreatedAt)
                .min(Instant::compareTo).orElse(null);
        long kitCount = mockProducts.stream()
                .flatMap(p -> variantRepository.findByProductId(p.getId()).stream())
                .filter(ProductVariant::isKit).count();
        long professionCount = professionRepository.findAll().stream().filter(Profession::isMockData).count();
        long orderCount = orderRepository.countByIsMockDataTrue();
        return new MockDataStatusDto(enabled, generatedAt, mockProducts.size(), (int) kitCount,
                (int) professionCount, (int) orderCount);
    }

    @Transactional
    public MockDataStatusDto enable(StaffUser staff) {
        List<Product> existing = productRepository.findAll().stream().filter(Product::isMockData).toList();
        if (existing.isEmpty()) {
            generate(staff);
        } else {
            Instant now = Instant.now();
            existing.forEach(p -> { p.setPublished(true); p.setPublishedAt(now); });
            productRepository.saveAll(existing);
            List<Category> cats = categoryRepository.findAll().stream().filter(Category::isMockData).toList();
            cats.forEach(c -> c.setPublished(true));
            categoryRepository.saveAll(cats);
        }
        refreshFlashDeals();
        return status();
    }

    /**
     * Puts roughly one mock variant in eight on a fresh flash deal so the storefront's Flash Deals
     * rail isn't empty in a demo. Redone on every enable — deadlines pass while the demo data sits
     * switched off, and an expired deal would leave the rail bare again.
     */
    private void refreshFlashDeals() {
        List<ProductVariant> variants = productRepository.findAll().stream()
                .filter(Product::isMockData)
                .flatMap(p -> variantRepository.findByProductId(p.getId()).stream())
                .filter(v -> !v.isDeleted() && !v.isKit() && v.getSellingPrice() != null)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        variants.forEach(v -> {
            v.setDealPrice(null);
            v.setDealEndsAt(null);
        });
        Collections.shuffle(variants, random);
        int dealCount = Math.max(8, variants.size() / 8);
        Instant now = Instant.now();
        for (int i = 0; i < Math.min(dealCount, variants.size()); i++) {
            ProductVariant v = variants.get(i);
            int percentOff = 10 + 5 * random.nextInt(5); // 10-30%
            BigDecimal dealPrice = v.getSellingPrice().multiply(BigDecimal.valueOf(100L - percentOff))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            if (dealPrice.signum() > 0 && dealPrice.compareTo(v.getSellingPrice()) < 0) {
                v.setDealPrice(dealPrice);
                v.setDealEndsAt(now.plus(2 + random.nextInt(5), ChronoUnit.DAYS));
            }
        }
        variantRepository.saveAll(variants);
    }

    @Transactional
    public MockDataStatusDto disable() {
        List<Product> existing = productRepository.findAll().stream().filter(Product::isMockData).toList();
        existing.forEach(p -> { p.setPublished(false); p.setPublishedAt(null); });
        productRepository.saveAll(existing);
        List<Category> cats = categoryRepository.findAll().stream().filter(Category::isMockData).toList();
        cats.forEach(c -> c.setPublished(false));
        categoryRepository.saveAll(cats);
        return status();
    }

    // ---- generation ----

    private void generate(StaffUser developer) {
        List<Category> realTopLevel = categoryRepository.findAll().stream()
                .filter(c -> c.getParent() == null && !c.isMockData())
                .toList();

        List<ProductVariant> allVariants = new ArrayList<>();
        List<Profession> professions = new ArrayList<>();

        for (Category top : realTopLevel) {
            List<CategoryTemplates.ProductTemplate> templates = CategoryTemplates.templatesFor(top.getSlug());
            if (templates.isEmpty()) continue; // a top-level category the templates don't know about — leave it alone
            CategoryTemplates.ProfessionSeed professionSeed = CategoryTemplates.professionFor(top.getSlug());
            Category subcategory = createMockSubcategory(top);

            Profession profession = null;
            if (professionSeed != null) {
                profession = createMockProfession(professionSeed.label(), professionSeed.slug());
                professions.add(profession);
            }

            List<ProductVariant> categoryVariants = new ArrayList<>();
            for (CategoryTemplates.ProductTemplate template : templates) {
                ProductVariant variant = createMockProduct(template, top, subcategory, developer);
                categoryVariants.add(variant);
            }
            allVariants.addAll(categoryVariants);

            if (profession != null && categoryVariants.size() >= 3) {
                createMockKit(profession, top, categoryVariants, developer);
            }
        }

        seedReviewsAndSales(allVariants, developer);
        seedRestockPressure(allVariants, developer);
    }

    /**
     * Makes the Forecast tab worth showing in a demo: ten products that are selling fast with little
     * left (two already sold out), and a reorder level on most of the catalogue so the "low stock"
     * badges have something to show. Stock is lowered BEFORE reorder levels are set, so seeding never
     * fires a low-stock alert — and alerts skip demo products regardless.
     */
    private void seedRestockPressure(List<ProductVariant> variants, StaffUser staff) {
        List<ProductVariant> pool = new ArrayList<>(variants);
        Collections.shuffle(pool, random);
        List<CustomerOrder> orders = List.of(createFakeOrder(), createFakeOrder(), createFakeOrder());

        for (int i = 0; i < Math.min(10, pool.size()); i++) {
            ProductVariant v = pool.get(i);
            int stock = i < 2 ? 0 : 1 + random.nextInt(6);
            stockService.setOnHand(v.getProduct().getId(), v.getId(), BigDecimal.valueOf(stock), null, "mock data seed", staff);

            int total = 30 + random.nextInt(30);    // units sold across the last few weeks
            int events = 3 + random.nextInt(3);
            for (int e = 0; e < events; e++) {
                ProductSaleEvent sale = new ProductSaleEvent();
                sale.setProduct(v.getProduct());
                sale.setVariant(v);
                sale.setOrder(orders.get(random.nextInt(orders.size())));
                sale.setQty(BigDecimal.valueOf(Math.max(1, total / events)));
                sale.setOccurredAt(Instant.now().minus(1 + random.nextInt(24), ChronoUnit.DAYS));
                saleEventRepository.save(sale);
            }
        }

        for (ProductVariant v : variants) {
            if (random.nextInt(100) < 60) {
                v.setReorderLevel(BigDecimal.valueOf(4 + random.nextInt(9)));
            }
        }
        variantRepository.saveAll(variants);
    }

    private Category createMockSubcategory(Category parent) {
        String name = parent.getName() + " — New Arrivals";
        Category sub = new Category();
        sub.setName(name);
        sub.setSlug(slugify(name) + "-" + shortId());
        sub.setParent(parent);
        sub.setPublished(true);
        sub.setMockData(true);
        return categoryRepository.save(sub);
    }

    private Profession createMockProfession(String label, String slugBase) {
        String slug = slugBase + "-" + shortId();
        var dto = kitService.createProfession(new ProfessionRequest(label, slug));
        Profession profession = professionRepository.findById(dto.id()).orElseThrow();
        profession.setMockData(true);
        return professionRepository.save(profession);
    }

    private ProductVariant createMockProduct(CategoryTemplates.ProductTemplate template, Category topCategory,
                                              Category subcategory, StaffUser developer) {
        BigDecimal price = randomPrice(template.priceMin(), template.priceMax());
        var variantInput = new SaveProductRequest.VariantInput(null, "Default", null, price.toPlainString(), true);
        var request = new SaveProductRequest(
                null, template.name(), template.description(), template.description(),
                null, null,
                List.of(topCategory.getId(), subcategory.getId()),
                true,
                random.nextInt(10) < 2, // ~20% popular
                random.nextInt(10) < 3, // ~30% new arrival
                List.of(variantInput));
        var dto = productService.saveProduct(null, request, developer);

        Product product = productRepository.findById(dto.id()).orElseThrow();
        product.setMockData(true);
        productRepository.save(product);

        ProductVariant variant = variantRepository.findByProductId(dto.id()).get(0);

        // Realistic, varied stock so "N in stock" looks genuinely populated, not identical everywhere.
        BigDecimal stock = BigDecimal.valueOf(5 + random.nextInt(46));
        stockService.setOnHand(product.getId(), variant.getId(), stock, price.multiply(BigDecimal.valueOf(0.55)),
                "mock data seed", developer);

        attachImages(product, variant, template.imageKeyword());
        return variant;
    }

    private void attachImages(Product product, ProductVariant variant, String keyword) {
        int photoCount = random.nextInt(100) < 18 ? 2 + random.nextInt(2) : 1; // ~18% get 2-3 photos
        for (int i = 0; i < photoCount; i++) {
            String url = fetchStockPhotoUrl(keyword, i);
            if (url == null) continue;
            ProductImage image = new ProductImage();
            image.setProduct(product);
            // Every demo product has one option, and a photo belongs to exactly one option (see PhotoOwnership).
            image.setVariant(variant);
            image.setUrl(url);
            image.setAltText(product.getName());
            image.setSortOrder(i);
            image.setPrimary(i == 0);
            imageRepository.save(image);
        }
    }

    /** Placeholder photos from Picsum: a seeded URL always returns the same real photo, different
     *  seeds give different photos, no API key, and it's hotlinked like any <img src> (nothing is
     *  fetched at seed time). They are NOT on-topic — source.unsplash.com (keyword search) was shut
     *  down and now returns 503, and every other keyword-search photo service found needs an API key
     *  or sits behind a bot check. Swap this one method to change where mock images come from, e.g.
     *  a curated pool of real beauty photos per keyword. */
    private String fetchStockPhotoUrl(String keyword, int index) {
        String seed = slugify(keyword) + "-" + shortId() + "-" + index;
        return "https://picsum.photos/seed/" + seed + "/800/600";
    }

    private void createMockKit(Profession profession, Category category, List<ProductVariant> candidates, StaffUser developer) {
        // A starter kit should be affordable and varied: take up to 4 evenly-spaced items from the
        // cheaper ~60% of the category, not the first few templates (which can be a KSh 21k facial bed).
        List<ProductVariant> byPrice = candidates.stream()
                .filter(v -> v.getSellingPrice() != null)
                .sorted(Comparator.comparing(ProductVariant::getSellingPrice))
                .toList();
        List<ProductVariant> cheaper = byPrice.subList(0, Math.min(byPrice.size(), Math.max(4, (int) Math.ceil(byPrice.size() * 0.6))));
        int count = Math.min(4, cheaper.size());
        List<ProductVariant> components = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            components.add(cheaper.get(i * (cheaper.size() - 1) / Math.max(1, count - 1)));
        }
        BigDecimal sumOfComponents = components.stream()
                .map(v -> v.getSellingPrice() == null ? BigDecimal.ZERO : v.getSellingPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal finalPrice = sumOfComponents.multiply(BigDecimal.valueOf(0.85)).setScale(0, RoundingMode.HALF_UP);

        var componentInputs = components.stream()
                .map(v -> new ComponentInput(v.getId(), "1"))
                .toList();
        var request = new SaveKitRequest(
                profession.getLabel() + " Starter Kit", profession.getId(), category.getId(),
                "A curated starter set for a " + profession.getLabel().toLowerCase(Locale.ROOT) + " — everything you need to get going.",
                finalPrice.toPlainString(), componentInputs);
        var dto = kitService.create(request);

        Product kitProduct = productRepository.findById(dto.productId()).orElseThrow();
        kitProduct.setMockData(true);
        productRepository.save(kitProduct);

        // A kit is a product+variant like any other; without an image it shows a blank tile.
        ProductVariant kitVariant = variantRepository.findByProductId(kitProduct.getId()).get(0);
        attachImages(kitProduct, kitVariant, profession.getLabel() + " kit");
    }

    private void seedReviewsAndSales(List<ProductVariant> variants, StaffUser developer) {
        List<CustomerOrder> fakeOrders = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            fakeOrders.add(createFakeOrder());
        }

        for (ProductVariant variant : variants) {
            if (variant.getSellingPrice() == null) continue;
            Product product = variant.getProduct();

            // Units sold — ~70% of products get some sales history, spread over the last ~90 days.
            if (random.nextInt(100) < 70) {
                int events = 1 + random.nextInt(3);
                for (int i = 0; i < events; i++) {
                    ProductSaleEvent sale = new ProductSaleEvent();
                    sale.setProduct(product);
                    sale.setVariant(variant);
                    sale.setOrder(fakeOrders.get(random.nextInt(fakeOrders.size())));
                    sale.setQty(BigDecimal.valueOf(1 + random.nextInt(4)));
                    sale.setOccurredAt(Instant.now().minus(random.nextInt(90), ChronoUnit.DAYS));
                    saleEventRepository.save(sale);
                }
            }

            // Reviews — ~45% of products get 1-4 approved reviews, rating skewed positive.
            if (random.nextInt(100) < 45) {
                int reviewCount = 1 + random.nextInt(4);
                // product_review is UNIQUE (order_id, product_id): each review of one product needs its own order.
                List<CustomerOrder> distinctOrders = new ArrayList<>(fakeOrders);
                Collections.shuffle(distinctOrders, random);
                for (int i = 0; i < reviewCount; i++) {
                    ProductReview review = new ProductReview();
                    review.setProduct(product);
                    review.setOrder(distinctOrders.get(i));
                    review.setRating(skewedRating());
                    review.setComment(CategoryTemplates.randomReviewComment(random));
                    review.setReviewerName(CategoryTemplates.randomReviewerName(random));
                    review.setReviewerPhone("2547" + String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000)));
                    review.setStatus(ProductReview.ReviewStatus.APPROVED);
                    reviewRepository.save(review);
                }
            }
        }
    }

    private CustomerOrder createFakeOrder() {
        BigDecimal subtotal = randomPrice(500, 8000);
        BigDecimal vat = subtotal.multiply(BigDecimal.valueOf(0.16)).setScale(2, RoundingMode.HALF_UP);
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("MOCK-" + shortId().toUpperCase(Locale.ROOT));
        order.setGuestEmail("demo" + shortId() + "@example.com");
        order.setGuestPhone("2547" + String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000)));
        order.setCustomerName(CategoryTemplates.randomReviewerName(random));
        order.setFulfilmentType(FulfilmentType.PICKUP);
        order.setStatus(OrderStatus.DELIVERED);
        order.setSubtotal(subtotal);
        order.setVatTotal(vat);
        order.setDeliveryFee(BigDecimal.ZERO);
        order.setGrandTotal(subtotal.add(vat));
        order.setPlacedAt(Instant.now().minus(random.nextInt(90), ChronoUnit.DAYS));
        order.setMockData(true);
        return orderRepository.save(order);
    }

    // ---- small helpers ----

    private BigDecimal randomPrice(int min, int max) {
        int value = min + random.nextInt(Math.max(1, max - min));
        return BigDecimal.valueOf(Math.round(value / 10.0) * 10);
    }

    private int skewedRating() {
        int roll = random.nextInt(100);
        if (roll < 55) return 5;
        if (roll < 85) return 4;
        if (roll < 96) return 3;
        return 2;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String slugify(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
