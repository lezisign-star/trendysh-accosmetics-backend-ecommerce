package com.nbh.backend.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductCombine;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductCombineRepository;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.stock.service.StockAvailabilityService;
import com.nbh.backend.variantjobs.VariantJob;
import com.nbh.backend.variantjobs.VariantJobRepository;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Combining products into one (owner, 2026-10-29). Staff who created the same product once per colour or size
 * ("Gel polish red", "Gel polish pink") join them under one main product. The main's configuration ({@link RangeService})
 * then holds every setting for all of them: the name, description, categories, the shared price and size and the
 * switches for what may differ. The joined products leave the product list.
 *
 * <ul>
 *   <li>Each variant keeps its own record (stock, price, barcodes, basket and order lines, its shop link), so moving it
 *       is only a change of product; staff confirm each one's colour, size and own part of the name.</li>
 *   <li>Prices are never changed: when they differ, the configuration lets them differ. The same for sizes.</li>
 *   <li>Photos follow their variant; a single-option product's unassigned photos go to that option first, so they keep
 *       showing. Reviews move to the main (one an order already wrote for the main stays where it was), and view and
 *       sale counts follow their variant.</li>
 *   <li>Every combine is written down ({@link ProductCombine}) and can be split back: the products, their variants'
 *       names and options, photos, reviews and counts go back. Prices and stock stay as they are by then.</li>
 * </ul>
 */
@Service
public class ProductCombineService {

    private static final int MAX_PART = 200;

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final CategoryRepository categories;
    private final ProductCombineRepository combines;
    private final VariantJobRepository jobs;
    private final ProductOptionsService productOptions;
    private final RangeService ranges;
    private final StockAvailabilityService availability;
    private final EntityManager em;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public ProductCombineService(ProductRepository products, ProductVariantRepository variants,
                                 ProductImageRepository images, CategoryRepository categories,
                                 ProductCombineRepository combines, VariantJobRepository jobs,
                                 ProductOptionsService productOptions, RangeService ranges,
                                 StockAvailabilityService availability, EntityManager em, ObjectMapper json,
                                 ApplicationEventPublisher events) {
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.categories = categories;
        this.combines = combines;
        this.jobs = jobs;
        this.productOptions = productOptions;
        this.ranges = ranges;
        this.availability = availability;
        this.em = em;
        this.json = json;
        this.events = events;
    }

    // ---------------------------------------------------------------- shapes

    public record PreviewRequest(UUID mainProductId, List<UUID> productIds) {}

    public record VariantPart(UUID id, String name, String namePart, String color, String size, String swatchHex,
                              BigDecimal price, BigDecimal stock, String photoUrl) {}

    public record ProductPart(UUID id, String name, boolean main, boolean published, int reviews,
                              List<VariantPart> variants) {}

    /** {@code blocks}: why it cannot be combined, in plain words; empty = it can. */
    public record Preview(UUID mainProductId, List<ProductPart> products, List<String> blocks) {}

    /** One variant as staff confirmed it: its own part of the name, colour, size and dot. */
    public record Row(UUID variantId, String namePart, String color, String size, String swatchHex) {}

    public record CombineRequest(UUID mainProductId, List<UUID> productIds, String name, List<Row> rows) {}

    public record Result(UUID combineId, UUID productId, int variants, int reviewsMoved, int reviewsKeptAside) {}

    public record CombineDto(UUID id, Instant combinedAt, String combinedBy, List<String> joinedNames, Instant splitAt,
                             String splitBy, boolean canSplit) {}

    /** What a variant was before, so a split puts it back. */
    public record VariantState(UUID id, String variantName, String namePart, String optionColor, String optionSize,
                               String swatchHex, String colorFamily, String optionLabel, Integer sizePosition,
                               int sortOrder, String description, UUID categoryId) {}

    public record MainState(UUID productId, String name, Instant rangeReviewedAt, BigDecimal sharedPrice,
                            String sharedSize, boolean priceVaries, boolean sizeVaries, boolean descriptionVaries,
                            boolean categoryVaries, List<VariantState> variants, List<UUID> assignedImageIds,
                            List<UUID> unprimaryImageIds) {}

    public record JoinedState(UUID productId, String name, boolean published, Instant publishedAt,
                              List<VariantState> variants, List<UUID> imageIds, List<UUID> assignedImageIds,
                              List<UUID> unprimaryImageIds, List<UUID> reviewIds) {}

    public record Snapshot(MainState main, List<JoinedState> joined) {}

    // ---------------------------------------------------------------- preview

    @Transactional(readOnly = true)
    public Preview preview(PreviewRequest in) {
        List<Product> ps = load(in == null ? null : in.mainProductId(), in == null ? null : in.productIds());
        Product main = ps.get(0);
        List<UUID> allVariantIds = new ArrayList<>();
        Map<UUID, List<ProductVariant>> live = new LinkedHashMap<>();
        for (Product p : ps) {
            List<ProductVariant> vs = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(p.getId());
            live.put(p.getId(), vs);
            vs.forEach(v -> allVariantIds.add(v.getId()));
        }
        Map<UUID, BigDecimal> stock = availability.available(allVariantIds);
        List<ProductPart> parts = new ArrayList<>();
        for (Product p : ps) {
            List<ProductVariant> vs = live.get(p.getId());
            List<ProductImage> photos = images.findByProductIdOrderBySortOrderAsc(p.getId());
            List<VariantPart> rows = new ArrayList<>();
            for (ProductVariant v : vs) {
                String photo = PhotoOwnership.coverFor(v.getId(), vs.size() <= 1, photos).map(ProductImage::getUrl)
                        .orElse(null);
                rows.add(new VariantPart(v.getId(), v.getVariantName(), v.getNamePart(), v.getOptionColor(),
                        v.getOptionSize(), v.getSwatchHex(), v.getSellingPrice(),
                        stock.getOrDefault(v.getId(), BigDecimal.ZERO), photo));
            }
            parts.add(new ProductPart(p.getId(), p.getName(), p == main, p.isPublished(), reviewCount(p.getId()), rows));
        }
        return new Preview(main.getId(), parts, blocks(ps, live));
    }

    // ---------------------------------------------------------------- combine

    @Transactional
    public Result combine(CombineRequest in, String staffName) {
        if (in == null) throw new UnprocessableException("Nothing to combine.");
        List<Product> ps = load(in.mainProductId(), in.productIds());
        Product main = ps.get(0);
        List<Product> joined = ps.subList(1, ps.size());
        Map<UUID, List<ProductVariant>> live = new LinkedHashMap<>();
        for (Product p : ps) live.put(p.getId(), variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(p.getId()));
        List<String> blocks = blocks(ps, live);
        if (!blocks.isEmpty()) throw new UnprocessableException(blocks.get(0));

        String name = RangeService.clean(in.name());
        if (name == null) throw new UnprocessableException("Give the combined product its name.");
        if (name.length() > MAX_PART) name = name.substring(0, MAX_PART).trim();
        Map<UUID, Row> rows = checkRows(in.rows(), live);

        // What everything was, before anything changes.
        MainState mainBefore = mainState(main, live.get(main.getId()));
        List<JoinedState> joinedBefore = new ArrayList<>();
        for (Product p : joined) joinedBefore.add(joinedState(p, live.get(p.getId())));

        // Photos: a single-option product's unassigned photos go to that option, so they keep showing once the
        // product has several. Then each joined product's photos move with their variants.
        List<UUID> mainAssigned = new ArrayList<>();
        List<UUID> mainUnprimary = new ArrayList<>();
        assignLoosePhotos(main, live.get(main.getId()), mainAssigned, mainUnprimary);
        List<JoinedState> joinedRecords = new ArrayList<>();
        int moved = 0;
        int keptAside = 0;
        for (int i = 0; i < joined.size(); i++) {
            Product p = joined.get(i);
            List<ProductVariant> vs = live.get(p.getId());
            List<UUID> assigned = new ArrayList<>();
            List<UUID> unprimary = new ArrayList<>();
            assignLoosePhotos(p, vs, assigned, unprimary);
            Set<UUID> ids = vs.stream().map(ProductVariant::getId).collect(Collectors.toSet());
            List<UUID> photoIds = new ArrayList<>();
            for (ProductImage image : images.findByProductIdOrderBySortOrderAsc(p.getId())) {
                if (image.getVariant() != null && ids.contains(image.getVariant().getId())) {
                    image.setProduct(main);
                    photoIds.add(image.getId());
                }
            }
            List<UUID> reviewIds = moveReviews(p.getId(), main.getId());
            moved += reviewIds.size();
            keptAside += reviewCount(p.getId());
            JoinedState before = joinedBefore.get(i);
            joinedRecords.add(new JoinedState(before.productId(), before.name(), before.published(),
                    before.publishedAt(), before.variants(), photoIds, assigned, unprimary, reviewIds));
        }
        em.flush();

        // The variants, in the order staff will see them: the main's own first, then each joined product's.
        List<ProductVariant> all = new ArrayList<>();
        for (Product p : ps) all.addAll(live.get(p.getId()));
        int position = 0;
        for (ProductVariant v : all) {
            Row row = rows.get(v.getId());
            String color = ProductOptionsService.clean(row.color());
            if (!Objects.equals(lower(color), lower(v.getOptionColor()))) {
                v.setSwatchHex(null);
                v.setColorFamily(null);
            }
            String hex = hex(row.swatchHex());
            if (color != null && hex != null) v.setSwatchHex(hex);
            v.setOptionColor(color);
            v.setOptionSize(ProductOptionsService.clean(row.size()));
            v.setOptionLabel(null);
            v.setNamePart(RangeService.clean(row.namePart()));
            v.setSortOrder(position++);
            v.setProduct(main);
        }
        variants.saveAll(all);
        variants.flush();
        List<UUID> joinedVariantIds = new ArrayList<>();
        for (Product p : joined) live.get(p.getId()).forEach(v -> joinedVariantIds.add(v.getId()));
        repoint(joinedVariantIds, main.getId());

        // The main now holds the configuration for all of them.
        configure(main, name, live.get(main.getId()), all);
        products.save(main);
        for (Product p : joined) {
            p.setDeleted(true);
            p.setPublished(false);
            p.setPublishedAt(null);
        }
        products.saveAll(joined);
        products.flush();
        productOptions.sync(main.getId());
        for (Product p : joined) productOptions.sync(p.getId());
        ranges.enforce(main.getId());

        ProductCombine record = new ProductCombine();
        record.setMainProductId(main.getId());
        record.setCombinedBy(staffName);
        record.setJoinedNames(joined.stream().map(Product::getName).collect(Collectors.joining("\n")));
        record.setSnapshot(write(new Snapshot(new MainState(mainBefore.productId(), mainBefore.name(),
                mainBefore.rangeReviewedAt(), mainBefore.sharedPrice(), mainBefore.sharedSize(),
                mainBefore.priceVaries(), mainBefore.sizeVaries(), mainBefore.descriptionVaries(),
                mainBefore.categoryVaries(), mainBefore.variants(), mainAssigned, mainUnprimary), joinedRecords)));
        combines.save(record);
        changed();
        return new Result(record.getId(), main.getId(), all.size(), moved, keptAside);
    }

    // ---------------------------------------------------------------- split back

    @Transactional(readOnly = true)
    public List<CombineDto> list(UUID productId) {
        List<ProductCombine> rows = combines.findByMainProductIdOrderByCreatedAtDesc(productId);
        UUID latestOpen = rows.stream().filter(c -> c.getSplitAt() == null).map(ProductCombine::getId).findFirst()
                .orElse(null);
        return rows.stream().map(c -> new CombineDto(c.getId(), c.getCreatedAt(), c.getCombinedBy(),
                Arrays.stream(c.getJoinedNames().split("\n")).filter(s -> !s.isBlank()).toList(), c.getSplitAt(),
                c.getSplitBy(), c.getId().equals(latestOpen))).toList();
    }

    @Transactional
    public void split(UUID combineId, String staffName) {
        ProductCombine record = combines.findById(combineId)
                .orElseThrow(() -> new NotFoundException("No such combine."));
        if (record.getSplitAt() != null) throw new ConflictException("This combine was already split back.");
        ProductCombine latest = combines.findByMainProductIdOrderByCreatedAtDesc(record.getMainProductId()).stream()
                .filter(c -> c.getSplitAt() == null).findFirst().orElse(record);
        if (!latest.getId().equals(record.getId())) {
            throw new ConflictException("Split back the later combine into this product first.");
        }
        Snapshot snap = read(record.getSnapshot());
        Product main = products.findById(record.getMainProductId())
                .orElseThrow(() -> new NotFoundException("The main product no longer exists."));
        MainState m = snap.main();
        main.setName(m.name());
        main.setRangeReviewedAt(m.rangeReviewedAt());
        main.setSharedPrice(m.sharedPrice());
        main.setSharedSize(m.sharedSize());
        main.setPriceVaries(m.priceVaries());
        main.setSizeVaries(m.sizeVaries());
        main.setDescriptionVaries(m.descriptionVaries());
        main.setCategoryVaries(m.categoryVaries());
        restoreVariants(m.variants(), main, main);
        restorePhotos(m.assignedImageIds(), m.unprimaryImageIds(), List.of(), main, main);
        products.save(main);

        List<UUID> touched = new ArrayList<>();
        for (JoinedState j : snap.joined()) {
            Product p = products.findById(j.productId()).orElse(null);
            if (p == null) continue;
            p.setDeleted(false);
            p.setPublished(j.published());
            p.setPublishedAt(j.publishedAt());
            products.save(p);
            List<UUID> back = restoreVariants(j.variants(), main, p);
            restorePhotos(j.assignedImageIds(), j.unprimaryImageIds(), j.imageIds(), main, p);
            em.flush();
            if (!j.reviewIds().isEmpty()) {
                em.createNativeQuery("UPDATE product_review SET product_id = :p WHERE id IN (:ids) AND product_id = :main")
                        .setParameter("p", p.getId()).setParameter("ids", j.reviewIds())
                        .setParameter("main", main.getId()).executeUpdate();
            }
            repoint(back, p.getId());
            touched.add(p.getId());
        }
        em.flush();
        productOptions.sync(main.getId());
        for (UUID id : touched) productOptions.sync(id);

        record.setSplitAt(Instant.now());
        record.setSplitBy(staffName);
        combines.save(record);
        changed();
    }

    // ---------------------------------------------------------------- rules

    /** The main first, then the others in the order given; each found, none twice. */
    private List<Product> load(UUID mainId, List<UUID> ids) {
        if (mainId == null) throw new UnprocessableException("Choose the main product.");
        LinkedHashSet<UUID> order = new LinkedHashSet<>();
        order.add(mainId);
        if (ids != null) ids.stream().filter(Objects::nonNull).forEach(order::add);
        if (order.size() < 2) throw new UnprocessableException("Choose at least two products to combine.");
        if (order.size() > 30) throw new UnprocessableException("Combine at most 30 products at a time.");
        List<Product> out = new ArrayList<>();
        for (UUID id : order) {
            out.add(products.findById(id).orElseThrow(() -> new NotFoundException("No product: " + id)));
        }
        return out;
    }

    private List<String> blocks(List<Product> ps, Map<UUID, List<ProductVariant>> live) {
        List<String> out = new ArrayList<>();
        Product main = ps.get(0);
        Set<UUID> openMains = combines.findByMainProductIdInAndSplitAtIsNull(
                        ps.stream().map(Product::getId).toList()).stream()
                .map(ProductCombine::getMainProductId).collect(Collectors.toSet());
        for (Product p : ps) {
            String n = "“" + p.getName() + "”";
            if (p.isDeleted()) out.add(n + " was removed. Restore it first, or leave it out.");
            if (variants.existsPackageVariant(p.getId())) out.add(n + " is a package. Packages can't be combined.");
            if (live.get(p.getId()).isEmpty()) out.add(n + " has nothing to sell, so there is nothing to move.");
            boolean working = jobs.findByProductIdOrderByCreatedAtDesc(p.getId()).stream()
                    .anyMatch(j -> !VariantJob.FINISHED.contains(j.getStatus()));
            if (working) out.add("Photos or descriptions are still being made for " + n + ". Combine once that is done.");
            if (p != main && openMains.contains(p.getId())) {
                out.add("Other products were combined into " + n + ". Choose it as the main, or split those back first.");
            }
        }
        return out;
    }

    /** Every variant has exactly one row, each with its own name part, and no two share a name or a colour and size. */
    private static Map<UUID, Row> checkRows(List<Row> rows, Map<UUID, List<ProductVariant>> live) {
        Set<UUID> expected = new LinkedHashSet<>();
        live.values().forEach(vs -> vs.forEach(v -> expected.add(v.getId())));
        Map<UUID, Row> byId = new HashMap<>();
        if (rows != null) for (Row r : rows) if (r != null && r.variantId() != null) byId.put(r.variantId(), r);
        if (!byId.keySet().equals(expected)) {
            throw new UnprocessableException("The products changed while you were combining them. Open the screen again.");
        }
        Set<String> names = new HashSet<>();
        Set<String> options = new HashSet<>();
        for (UUID id : expected) {
            Row r = byId.get(id);
            String part = RangeService.clean(r.namePart());
            if (part == null) throw new UnprocessableException("Give every colour or size its name.");
            if (part.length() > MAX_PART) throw new UnprocessableException("“" + part + "” is too long.");
            if (!names.add(part.toLowerCase(Locale.ROOT))) {
                throw new UnprocessableException("Two would both be called “" + part + "”.");
            }
            String color = ProductOptionsService.clean(r.color());
            String size = ProductOptionsService.clean(r.size());
            if (color != null || size != null) {
                String key = lower(color) + "|" + lower(size);
                if (!options.add(key)) {
                    String label = new com.nbh.backend.variantgen.VariantCombos.Combo(color, size).label();
                    throw new UnprocessableException("Two would both be “" + label + "”. Give each its own colour or size.");
                }
            }
        }
        return byId;
    }

    /**
     * The main's configuration after the combine. A main not configured yet takes what its own variants suggest; then
     * the price and size are allowed to differ wherever the combined variants don't all agree, so no price changes.
     */
    private void configure(Product main, String name, List<ProductVariant> own, List<ProductVariant> all) {
        if (main.getRangeReviewedAt() == null) {
            RangeService.Config suggested = RangeService.suggest(main, own);
            main.setDescriptionVaries(suggested.descriptionVaries());
            main.setCategoryVaries(suggested.categoryVaries());
            main.setPriceVaries(false);
            main.setSizeVaries(false);
            main.setSharedPrice(null);
            main.setSharedSize(null);
            main.setRangeReviewedAt(Instant.now());
        }
        main.setName(name);
        Set<BigDecimal> prices = all.stream().map(ProductVariant::getSellingPrice).filter(Objects::nonNull)
                .map(BigDecimal::stripTrailingZeros).collect(Collectors.toSet());
        boolean missingPrice = all.stream().anyMatch(v -> v.getSellingPrice() == null);
        if (prices.size() > 1 || missingPrice || prices.isEmpty()) {
            main.setPriceVaries(true);
        } else if (!main.isPriceVaries()) {
            main.setSharedPrice(all.get(0).getSellingPrice());
        }
        if (main.isPriceVaries() && main.getSharedPrice() == null) main.setSharedPrice(mostCommon(all, ProductVariant::getSellingPrice));
        Set<String> sizes = all.stream().map(v -> lower(ProductOptionsService.clean(v.getOptionSize())))
                .collect(Collectors.toSet());
        if (sizes.size() > 1) {
            main.setSizeVaries(true);
        } else if (!main.isSizeVaries()) {
            main.setSharedSize(ProductOptionsService.clean(all.get(0).getOptionSize()));
        }
    }

    /** Moves a joined product's reviews to the main; one whose order already reviewed the main stays. Returns the moved ids. */
    @SuppressWarnings("unchecked")
    private List<UUID> moveReviews(UUID from, UUID to) {
        List<UUID> ids = ((List<Object>) em.createNativeQuery("""
                        SELECT r.id FROM product_review r
                        WHERE r.product_id = :from
                          AND NOT EXISTS (SELECT 1 FROM product_review m WHERE m.product_id = :to AND m.order_id = r.order_id)
                        """).setParameter("from", from).setParameter("to", to).getResultList())
                .stream().map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString())).toList();
        if (!ids.isEmpty()) {
            em.createNativeQuery("UPDATE product_review SET product_id = :to WHERE id IN (:ids)")
                    .setParameter("to", to).setParameter("ids", ids).executeUpdate();
        }
        return ids;
    }

    /** View and sale counts and waiting AI photos follow their variants to {@code productId}. */
    private void repoint(List<UUID> variantIds, UUID productId) {
        if (variantIds.isEmpty()) return;
        for (String table : List.of("product_sale_event", "product_view_event", "image_draft")) {
            em.createNativeQuery("UPDATE " + table + " SET product_id = :p WHERE variant_id IN (:ids)")
                    .setParameter("p", productId).setParameter("ids", variantIds).executeUpdate();
        }
    }

    /** A product with one option: its unassigned photos become that option's, so they keep showing. */
    private void assignLoosePhotos(Product p, List<ProductVariant> vs, List<UUID> assigned, List<UUID> unprimary) {
        if (vs.size() != 1) return;
        ProductVariant only = vs.get(0);
        List<ProductImage> photos = images.findByProductIdOrderBySortOrderAsc(p.getId());
        boolean hasMain = photos.stream()
                .anyMatch(i -> i.getVariant() != null && i.getVariant().getId().equals(only.getId()) && i.isPrimary());
        for (ProductImage image : photos) {
            if (image.getVariant() != null) continue;
            image.setVariant(only);
            assigned.add(image.getId());
            if (image.isPrimary() && hasMain) {
                image.setPrimary(false);
                unprimary.add(image.getId());
            }
        }
    }

    private List<UUID> restoreVariants(List<VariantState> states, Product main, Product to) {
        List<UUID> back = new ArrayList<>();
        for (VariantState s : states) {
            ProductVariant v = variants.findById(s.id()).orElse(null);
            if (v == null || !v.getProduct().getId().equals(main.getId())) continue;
            v.setProduct(to);
            v.setVariantName(s.variantName());
            v.setNamePart(s.namePart());
            v.setOptionColor(s.optionColor());
            v.setOptionSize(s.optionSize());
            v.setSwatchHex(s.swatchHex());
            v.setColorFamily(s.colorFamily());
            v.setOptionLabel(s.optionLabel());
            v.setSizePosition(s.sizePosition());
            v.setSortOrder(s.sortOrder());
            v.setDescription(s.description());
            Category c = s.categoryId() == null ? null : categories.findById(s.categoryId()).orElse(null);
            v.setCategory(c);
            variants.save(v);
            back.add(v.getId());
        }
        return back;
    }

    private void restorePhotos(List<UUID> assigned, List<UUID> unprimary, List<UUID> movedIds, Product main, Product to) {
        for (UUID id : movedIds) {
            images.findById(id).filter(i -> i.getProduct().getId().equals(main.getId())).ifPresent(i -> i.setProduct(to));
        }
        for (UUID id : assigned) images.findById(id).ifPresent(i -> i.setVariant(null));
        for (UUID id : unprimary) images.findById(id).ifPresent(i -> i.setPrimary(true));
    }

    private MainState mainState(Product p, List<ProductVariant> vs) {
        return new MainState(p.getId(), p.getName(), p.getRangeReviewedAt(), p.getSharedPrice(), p.getSharedSize(),
                p.isPriceVaries(), p.isSizeVaries(), p.isDescriptionVaries(), p.isCategoryVaries(), states(vs),
                List.of(), List.of());
    }

    private JoinedState joinedState(Product p, List<ProductVariant> vs) {
        return new JoinedState(p.getId(), p.getName(), p.isPublished(), p.getPublishedAt(), states(vs), List.of(),
                List.of(), List.of(), List.of());
    }

    private static List<VariantState> states(List<ProductVariant> vs) {
        return vs.stream().map(v -> new VariantState(v.getId(), v.getVariantName(), v.getNamePart(), v.getOptionColor(),
                v.getOptionSize(), v.getSwatchHex(), v.getColorFamily(), v.getOptionLabel(), v.getSizePosition(),
                v.getSortOrder(), v.getDescription(), v.getCategory() == null ? null : v.getCategory().getId())).toList();
    }

    private int reviewCount(UUID productId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM product_review WHERE product_id = :p")
                .setParameter("p", productId).getSingleResult()).intValue();
    }

    private void changed() {
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
    }

    private String write(Snapshot s) {
        try {
            return json.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not record the combine", e);
        }
    }

    private Snapshot read(String text) {
        try {
            return json.readValue(text, Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the combine's record", e);
        }
    }

    private static String hex(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.matches("#[0-9A-Fa-f]{6}") ? t.toUpperCase(Locale.ROOT) : null;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static <T> T mostCommon(List<ProductVariant> vs, Function<ProductVariant, T> of) {
        Map<T, Long> counts = vs.stream().map(of).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(t -> t, LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
}
