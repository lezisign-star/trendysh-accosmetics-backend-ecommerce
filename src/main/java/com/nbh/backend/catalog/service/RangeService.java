package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A range's configuration (owner, 2026-10-17). A product with several variants is not a product of its own: it is the
 * configuration its variants come from. It holds what they share (the common name, price, size, description,
 * categories and the group photo) and switches saying which may differ per variant; each variant is an independent
 * product that keeps its own part of the name, colour and code, photos and stock.
 *
 * <ul>
 *   <li><b>Name:</b> always forced. A variant is called "common name – its own part"; renaming the configuration renames
 *       the common part of every variant and keeps each one's own part.</li>
 *   <li><b>Price, size, description, category:</b> when the switch is off every variant has the shared value; when on,
 *       each may have its own. Changing a shared value moves the variants that had the old shared value with it, and
 *       "asks each time" about the others: {@link #preview} lists them, staff tick which to bring in line.</li>
 *   <li><b>Converting today's ranges:</b> until staff confirm the configuration ({@code rangeReviewedAt}), nothing about
 *       the range is changed; {@link #get} suggests the configuration from what most variants share.</li>
 * </ul>
 * Variants keep their stored price, size, name and description, so the shop, cart and checkout read them as before.
 */
@Service
public class RangeService {

    public static final String DASH = " – ";
    private static final int MAX_NAME = 250;

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final ProductOptionsService productOptions;
    private final ApplicationEventPublisher events;

    public RangeService(ProductRepository products, ProductVariantRepository variants, CategoryRepository categories,
                        ProductOptionsService productOptions, ApplicationEventPublisher events) {
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.productOptions = productOptions;
        this.events = events;
    }

    // ---------------------------------------------------------------- shapes

    /** One variant as the configuration sees it. */
    public record VariantRow(UUID id, String name, String namePart, BigDecimal price, String size, boolean ownDescription,
                             UUID categoryId, String categoryName) {}

    /** The configuration as stored, or (before it is confirmed) as suggested from the variants. */
    public record Config(String name, String description, BigDecimal sharedPrice, String sharedSize, List<UUID> categoryIds,
                         boolean priceVaries, boolean sizeVaries, boolean descriptionVaries, boolean categoryVaries,
                         String groupPhotoUrl, Map<UUID, String> nameParts) {}

    /** {@code suggested}: true while the range has not been confirmed (the config is a suggestion, nothing is applied). */
    public record RangeDto(UUID productId, boolean reviewed, Instant reviewedAt, boolean suggested, Config config,
                           List<VariantRow> variants, List<String> notes) {}

    /** What staff save. {@code nameParts}: own parts to change (variant id → part), e.g. while confirming. */
    public record RangeInput(String name, String description, BigDecimal sharedPrice, String sharedSize,
                             List<UUID> categoryIds, boolean priceVaries, boolean sizeVaries, boolean descriptionVaries,
                             boolean categoryVaries, String groupPhotoUrl, Map<UUID, String> nameParts,
                             BringInLine bringInLine) {}

    /** The variants with their own value that staff ticked to take the shared value. */
    public record BringInLine(List<UUID> price, List<UUID> size, List<UUID> description, List<UUID> category) {
        static BringInLine none() {
            return new BringInLine(List.of(), List.of(), List.of(), List.of());
        }
    }

    /** A variant that has its own value where the new shared value would differ: staff are asked about these. */
    public record Own(UUID id, String name, String value) {}

    /**
     * What a save would do: how many variants follow each shared value without asking, and the ones with their own value
     * to ask about. {@code renamed}: variants whose name changes.
     */
    public record Preview(int renamed, int priceFollow, List<Own> price, int sizeFollow, List<Own> size,
                          int descriptionFollow, List<Own> description, int categoryFollow, List<Own> category,
                          List<String> notes) {}

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public RangeDto get(UUID productId) {
        Product p = product(productId);
        List<ProductVariant> vs = live(p);
        boolean reviewed = p.getRangeReviewedAt() != null;
        Config config = reviewed ? stored(p, vs) : suggest(p, vs);
        List<String> notes = new ArrayList<>();
        if (!reviewed) {
            notes.addAll(suggestionNotes(p, vs, config));
        }
        return new RangeDto(p.getId(), reviewed, p.getRangeReviewedAt(), !reviewed, config, rows(vs, config), notes);
    }

    private Config stored(Product p, List<ProductVariant> vs) {
        Map<UUID, String> parts = new LinkedHashMap<>();
        for (ProductVariant v : vs) parts.put(v.getId(), partOf(v, p.getName()));
        return new Config(p.getName(), p.getDescription(), p.getSharedPrice(), p.getSharedSize(), categoryIds(p),
                p.isPriceVaries(), p.isSizeVaries(), p.isDescriptionVaries(), p.isCategoryVaries(), p.getGroupPhotoUrl(),
                parts);
    }

    /**
     * The configuration most variants already agree on: the common name is the start most variant names share before
     * the dash (else the product's name), the shared price and size are the most common ones, and a value varies when the
     * variants don't all have it.
     */
    static Config suggest(Product p, List<ProductVariant> vs) {
        String common = commonName(p.getName(), vs.stream().map(ProductVariant::getVariantName).toList());
        BigDecimal price = mostCommon(vs, ProductVariant::getSellingPrice);
        String size = mostCommon(vs, v -> ProductOptionsService.clean(v.getOptionSize()));
        boolean priceVaries = vs.stream().map(ProductVariant::getSellingPrice).filter(Objects::nonNull)
                .map(BigDecimal::stripTrailingZeros).distinct().count() > 1;
        boolean sizeVaries = vs.stream().map(v -> lower(ProductOptionsService.clean(v.getOptionSize())))
                .distinct().count() > 1;
        boolean descriptionVaries = vs.stream().anyMatch(v -> v.getDescription() != null && !v.getDescription().isBlank());
        boolean categoryVaries = vs.stream().anyMatch(v -> v.getCategory() != null);
        Map<UUID, String> parts = new LinkedHashMap<>();
        for (ProductVariant v : vs) parts.put(v.getId(), partOf(v, common));
        return new Config(common, p.getDescription(), price, size, categoryIds(p), priceVaries, sizeVaries,
                descriptionVaries, categoryVaries, p.getGroupPhotoUrl(), parts);
    }

    private static List<String> suggestionNotes(Product p, List<ProductVariant> vs, Config c) {
        List<String> notes = new ArrayList<>();
        if (!Objects.equals(c.name(), p.getName())) {
            notes.add("Suggested common name \"" + c.name() + "\" (now \"" + p.getName() + "\"), from the variants' names.");
        }
        if (c.sharedPrice() != null) {
            long same = vs.stream().filter(v -> sameMoney(v.getSellingPrice(), c.sharedPrice())).count();
            long own = vs.size() - same;
            notes.add("Shared price KSh " + c.sharedPrice().stripTrailingZeros().toPlainString() + " (" + same
                    + " variant" + (same == 1 ? "" : "s") + ")" + (own > 0 ? "; " + own + " keep their own" : "") + ".");
        }
        long ownText = vs.stream().filter(v -> v.getDescription() != null && !v.getDescription().isBlank()).count();
        if (ownText > 0) {
            notes.add(ownText + " variant" + (ownText == 1 ? " has its" : "s have their") + " own description.");
        }
        return notes;
    }

    private List<VariantRow> rows(List<ProductVariant> vs, Config c) {
        List<VariantRow> out = new ArrayList<>();
        for (ProductVariant v : vs) {
            Category cat = v.getCategory();
            out.add(new VariantRow(v.getId(), v.getVariantName(), c.nameParts().get(v.getId()), v.getSellingPrice(),
                    ProductOptionsService.clean(v.getOptionSize()),
                    v.getDescription() != null && !v.getDescription().isBlank(),
                    cat == null ? null : cat.getId(), cat == null ? null : cat.getName()));
        }
        return out;
    }

    // ---------------------------------------------------------------- ask each time

    @Transactional(readOnly = true)
    public Preview preview(UUID productId, RangeInput in) {
        Product p = product(productId);
        List<ProductVariant> vs = live(p);
        check(in, vs);
        // Before the first confirmation the "old shared value" is the suggested one: what most variants already have.
        Config before = p.getRangeReviewedAt() != null ? stored(p, vs) : suggest(p, vs);
        String name = cleanName(in.name());
        int renamed = 0;
        for (ProductVariant v : vs) {
            String part = partFor(v, in, before.name());
            if (!Objects.equals(fullName(name, part), v.getVariantName())) renamed++;
        }
        List<Own> price = new ArrayList<>(), size = new ArrayList<>(), desc = new ArrayList<>(), cat = new ArrayList<>();
        int pf = 0, sf = 0, df = 0, cf = 0;
        for (ProductVariant v : vs) {
            String label = fullName(name, partFor(v, in, before.name())); // as it will be called
            if (in.sharedPrice() != null && !sameMoney(v.getSellingPrice(), in.sharedPrice())) {
                if (!in.priceVaries() || sameMoney(v.getSellingPrice(), before.sharedPrice())) pf++;
                else price.add(new Own(v.getId(), label, money(v.getSellingPrice())));
            }
            String vSize = ProductOptionsService.clean(v.getOptionSize());
            String newSize = ProductOptionsService.clean(in.sharedSize());
            if (in.sizeVaries() && newSize != null && !sameText(vSize, newSize)) {
                if (vSize == null || sameText(vSize, before.sharedSize())) sf++;
                else size.add(new Own(v.getId(), label, vSize));
            }
            boolean ownText = v.getDescription() != null && !v.getDescription().isBlank();
            if (ownText) {
                if (!in.descriptionVaries()) df++;
                else if (!Objects.equals(clean(in.description()), clean(before.description()))) {
                    desc.add(new Own(v.getId(), label, shorten(v.getDescription())));
                }
            }
            if (v.getCategory() != null) {
                if (!in.categoryVaries()) cf++;
                else if (!sameIds(in.categoryIds(), before.categoryIds())) {
                    cat.add(new Own(v.getId(), label, v.getCategory().getName()));
                }
            }
        }
        List<String> notes = new ArrayList<>();
        if (!in.descriptionVaries() && df > 0) {
            notes.add(df + " variant" + (df == 1 ? "'s own description is" : "s' own descriptions are")
                    + " removed: every variant shows the shared description.");
        }
        if (!in.categoryVaries() && cf > 0) {
            notes.add(cf + " variant" + (cf == 1 ? " goes" : "s go") + " back to the shared categories.");
        }
        return new Preview(renamed, pf, price, sf, size, df, desc, cf, cat, notes);
    }

    // ---------------------------------------------------------------- saving

    @Transactional
    public RangeDto save(UUID productId, RangeInput in) {
        Product p = product(productId);
        List<ProductVariant> vs = live(p);
        check(in, vs);
        Config before = p.getRangeReviewedAt() != null ? stored(p, vs) : suggest(p, vs);
        BringInLine tick = in.bringInLine() == null ? BringInLine.none() : in.bringInLine();
        Set<UUID> tickPrice = ids(tick.price()), tickSize = ids(tick.size()), tickDesc = ids(tick.description()),
                tickCat = ids(tick.category());
        boolean descriptionChanged = !Objects.equals(clean(in.description()), clean(before.description()));
        boolean categoriesChanged = !sameIds(in.categoryIds(), before.categoryIds());

        String name = cleanName(in.name());
        String newSize = ProductOptionsService.clean(in.sharedSize());
        for (ProductVariant v : vs) {
            String part = partFor(v, in, before.name());
            v.setNamePart(part);
            v.setVariantName(fullName(name, part));
            if (in.sharedPrice() != null && (!in.priceVaries() || tickPrice.contains(v.getId())
                    || v.getSellingPrice() == null || sameMoney(v.getSellingPrice(), before.sharedPrice()))) {
                v.setSellingPrice(in.sharedPrice());
            }
            if (in.sizeVaries() && newSize != null) {
                String vSize = ProductOptionsService.clean(v.getOptionSize());
                if (vSize == null || tickSize.contains(v.getId()) || sameText(vSize, before.sharedSize())) {
                    v.setOptionSize(newSize);
                }
            }
            if (!in.descriptionVaries() || (descriptionChanged && tickDesc.contains(v.getId()))) {
                v.setDescription(null); // the shop shows the shared description
            }
            if (!in.categoryVaries() || (categoriesChanged && tickCat.contains(v.getId()))) {
                v.setCategory(null);
            }
        }
        variants.saveAll(vs);

        p.setName(name);
        p.setDescription(clean(in.description()));
        p.setSharedPrice(in.sharedPrice());
        p.setSharedSize(newSize);
        p.setPriceVaries(in.priceVaries());
        p.setSizeVaries(in.sizeVaries());
        p.setDescriptionVaries(in.descriptionVaries());
        p.setCategoryVaries(in.categoryVaries());
        p.setGroupPhotoUrl(blankToNull(in.groupPhotoUrl()));
        if (in.categoryIds() != null) {
            List<Category> cats = new ArrayList<>();
            for (UUID id : in.categoryIds()) {
                cats.add(categories.findById(id).orElseThrow(() -> new NotFoundException("No such category.")));
            }
            p.getCategories().clear();
            p.getCategories().addAll(cats);
        }
        p.setRangeReviewedAt(Instant.now());
        products.save(p);
        if (in.sizeVaries()) {
            productOptions.sync(p.getId());
        }
        products.touch(p.getId(), Instant.now());
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        return get(productId);
    }

    /**
     * Puts the configuration's rules back on its variants after they were saved some other way (one variant's page, the
     * product editor, a job that made new variants): the forced name, and the shared price, description and category
     * where the range doesn't let them differ. Does nothing for a range not confirmed yet.
     */
    @Transactional
    public void enforce(UUID productId) {
        enforce(productId, false);
    }

    /** {@code fromNames}: read each variant's own part again from its name (a save that sent whole names). */
    @Transactional
    public void enforce(UUID productId, boolean fromNames) {
        Product p = products.findById(productId).orElse(null);
        if (p == null || p.getRangeReviewedAt() == null) return;
        List<ProductVariant> vs = live(p);
        for (ProductVariant v : vs) {
            if (fromNames) v.setNamePart(null);
            String part = partOf(v, p.getName());
            v.setNamePart(part);
            v.setVariantName(fullName(p.getName(), part));
            if (!p.isPriceVaries() && p.getSharedPrice() != null) v.setSellingPrice(p.getSharedPrice());
            if (!p.isDescriptionVaries()) v.setDescription(null);
            if (!p.isCategoryVaries()) v.setCategory(null);
        }
        variants.saveAll(vs);
    }

    // ---------------------------------------------------------------- rules

    private static void check(RangeInput in, List<ProductVariant> vs) {
        if (in == null) throw new UnprocessableException("Nothing to save.");
        if (cleanName(in.name()) == null) throw new UnprocessableException("Give the range its common name.");
        if (in.sharedPrice() != null && in.sharedPrice().signum() <= 0) {
            throw new UnprocessableException("The shared price must be more than 0.");
        }
        if (!in.priceVaries() && in.sharedPrice() == null) {
            throw new UnprocessableException("Give the shared price, or let prices differ per variant.");
        }
        if (in.nameParts() != null) {
            Set<String> seen = new HashSet<>();
            Map<UUID, String> parts = new HashMap<>();
            for (ProductVariant v : vs) parts.put(v.getId(), partOf(v, null));
            parts.putAll(in.nameParts());
            for (Map.Entry<UUID, String> e : parts.entrySet()) {
                String part = clean(e.getValue());
                if (part != null && !seen.add(part.toLowerCase(Locale.ROOT))) {
                    throw new UnprocessableException("Two variants would both be called \"" + part + "\".");
                }
            }
        }
    }

    /** The part staff sent for this variant, else the part it has now (worked out from its name and the old common name). */
    private static String partFor(ProductVariant v, RangeInput in, String oldCommon) {
        if (in.nameParts() != null && in.nameParts().containsKey(v.getId())) {
            return clean(in.nameParts().get(v.getId()));
        }
        return partOf(v, oldCommon);
    }

    /**
     * A variant's own part of its name: what is stored, else its name after "common name – ", else after any dash, else
     * its colour/size label, else its name without the common name in front.
     */
    static String partOf(ProductVariant v, String common) {
        String stored = clean(v.getNamePart());
        if (stored != null) return stored;
        String name = clean(v.getVariantName());
        if (name != null && common != null && name.toLowerCase(Locale.ROOT).startsWith((common + DASH).toLowerCase(Locale.ROOT))) {
            return clean(name.substring((common + DASH).length()));
        }
        if (name != null) {
            int dash = name.lastIndexOf(DASH);
            if (dash >= 0) return clean(name.substring(dash + DASH.length()));
        }
        String label = clean(v.getOptionLabel());
        if (label != null) return label;
        if (name != null && common != null && name.toLowerCase(Locale.ROOT).startsWith(common.toLowerCase(Locale.ROOT) + " ")) {
            return clean(name.substring(common.length()));
        }
        return name;
    }

    /** "Roniki Gel – Bubblegum Pink RE106"; just the common name when the variant has no part of its own. */
    public static String fullName(String common, String part) {
        String full = part == null || part.isBlank() ? common : common + DASH + part;
        return full.length() > MAX_NAME ? full.substring(0, MAX_NAME).trim() : full;
    }

    /** The start most names share before their dash; the product's name when they share none. */
    static String commonName(String productName, List<String> names) {
        Map<String, Long> starts = names.stream().filter(Objects::nonNull).filter(n -> n.contains(DASH))
                .map(n -> n.substring(0, n.indexOf(DASH)).trim()).filter(s -> !s.isEmpty())
                .collect(Collectors.groupingBy(s -> s, LinkedHashMap::new, Collectors.counting()));
        return starts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .filter(s -> starts.get(s) * 2 > names.size() - 1) // most of them, the product's own variant aside
                .orElse(productName == null ? "" : productName.trim());
    }

    private static <T> T mostCommon(List<ProductVariant> vs, Function<ProductVariant, T> of) {
        Map<Object, Long> counts = new LinkedHashMap<>();
        Map<Object, T> firstOf = new HashMap<>();
        for (ProductVariant v : vs) {
            T value = of.apply(v);
            if (value == null) continue;
            Object key = value instanceof BigDecimal b ? b.stripTrailingZeros() : value instanceof String s ? s.toLowerCase(Locale.ROOT) : value;
            counts.merge(key, 1L, Long::sum);
            firstOf.putIfAbsent(key, value);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> firstOf.get(e.getKey())).orElse(null);
    }

    // ---------------------------------------------------------------- helpers

    private Product product(UUID productId) {
        return products.findById(productId).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such range."));
    }

    private List<ProductVariant> live(Product p) {
        return variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(p.getId()).stream()
                .filter(v -> !v.isKit()).toList();
    }

    private static List<UUID> categoryIds(Product p) {
        return p.getCategories().stream().map(Category::getId).toList();
    }

    private static Set<UUID> ids(List<UUID> list) {
        return list == null ? Set.of() : new HashSet<>(list);
    }

    private static boolean sameIds(List<UUID> a, List<UUID> b) {
        return new HashSet<>(a == null ? List.of() : a).equals(new HashSet<>(b == null ? List.of() : b));
    }

    static boolean sameMoney(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static boolean sameText(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static String money(BigDecimal b) {
        return b == null ? "no price" : "KSh " + b.stripTrailingZeros().toPlainString();
    }

    private static String shorten(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 80 ? t.substring(0, 77) + "…" : t;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[ \\t]+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    private static String cleanName(String s) {
        String t = clean(s);
        return t == null ? null : t.replace(DASH.trim(), "-").trim(); // a dash would split the name in the wrong place
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
