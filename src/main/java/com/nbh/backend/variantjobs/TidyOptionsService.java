package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.ColorFamily;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.ProductOptionsService;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.variantgen.VariantCombos;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * "Tidy colours & sizes" (VARIANTS_PLAN.md phase 6): products made before variants v2 whose variants are told apart
 * only by their names ("Gel polish – Ruby", "50ml"), so the shop's colour/size picker and filters can't use them. Each
 * variant gets a suggestion read from its name (a size like "50ml" or "Large", a colour the built-in shade list
 * knows); staff confirm or correct it and save, or leave a product as it is when its options are not colours or sizes
 * ("Pack of 3"). Either way the product leaves the list.
 */
@Service
public class TidyOptionsService {

    /** One variant as the screen shows it: its name and option, and the colour/size suggested (or already set). */
    public record VariantRow(UUID variantId, String name, String option, String color, String size, String swatchHex,
                             String colorFamily, boolean alreadySet) {}

    public record ProductRow(UUID productId, String productName, List<VariantRow> variants) {}

    public record Page(int total, List<ProductRow> products) {}

    /** What staff confirmed for one variant; blanks clear. */
    public record VariantChoice(UUID variantId, String color, String size, String swatchHex, String colorFamily) {}

    private static final Pattern SIZE = Pattern.compile(
            "(?i)^(\\d+(?:[.,]\\d+)?\\s*(ml|l|ltr|litre|liter|g|gm|kg|mg|oz|cm|mm|m|pcs|pc|pieces|piece|pack|packs|"
                    + "sheets|pairs|pair|ft|in|inch|inches|tips|rolls)|xxs|xs|s|m|l|xl|xxl|xxxl|small|medium|large|"
                    + "extra large|mini|travel|travel size|full size|jumbo)$");
    private static final Pattern SPLIT = Pattern.compile("\\s*[/|,]\\s*|\\s+[-–—]\\s+");
    /** A size written at the start or end of a part ("Clear 100g", "100g Clear"). */
    private static final Pattern SIZE_PART = Pattern.compile(
            "(?i)^(\\d+(?:[.,]\\d+)?\\s*(?:ml|l|ltr|g|gm|kg|mg|oz|cm|mm|pcs|pc|pieces|pack|sheets|pairs|tips))\\s+(.+)$"
                    + "|^(.+?)\\s+(\\d+(?:[.,]\\d+)?\\s*(?:ml|l|ltr|g|gm|kg|mg|oz|cm|mm|pcs|pc|pieces|pack|sheets|pairs|tips))$");

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductOptionsService productOptions;
    private final ApplicationEventPublisher events;

    public TidyOptionsService(ProductRepository products, ProductVariantRepository variants,
                              ProductOptionsService productOptions, ApplicationEventPublisher events) {
        this.products = products;
        this.variants = variants;
        this.productOptions = productOptions;
        this.events = events;
    }

    /** The products waiting to be tidied, alphabetically, at most {@code limit} of them (with the total). */
    @Transactional(readOnly = true)
    public Page list(int limit) {
        Map<Product, List<ProductVariant>> byProduct = waiting();
        List<ProductRow> rows = byProduct.entrySet().stream()
                .sorted(Comparator.comparing(e -> Objects.toString(e.getKey().getName(), "")))
                .limit(Math.max(1, Math.min(limit, 200)))
                .map(e -> new ProductRow(e.getKey().getId(), e.getKey().getName(),
                        e.getValue().stream().map(TidyOptionsService::suggest).toList()))
                .toList();
        return new Page(byProduct.size(), rows);
    }

    /** How many products are waiting (for Needs attention). */
    @Transactional(readOnly = true)
    public int count() {
        return waiting().size();
    }

    /** Saves staff's colours and sizes for a product's variants and takes the product off the list. */
    @Transactional
    public int save(UUID productId, List<VariantChoice> choices) {
        Product product = products.findById(productId).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such product."));
        Map<UUID, ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId).stream()
                .collect(Collectors.toMap(ProductVariant::getId, v -> v));
        int changed = 0;
        for (VariantChoice c : choices == null ? List.<VariantChoice>of() : choices) {
            ProductVariant v = live.get(c.variantId());
            if (v == null) {
                throw new IllegalArgumentException("A variant on the list is not on this product any more. Reload and try again.");
            }
            String color = ProductOptionsService.clean(c.color());
            String size = ProductOptionsService.clean(c.size());
            String hex = null;
            if (c.swatchHex() != null && !c.swatchHex().isBlank()) {
                hex = VariantCombos.normaliseHex(c.swatchHex());
                if (hex == null) {
                    throw new IllegalArgumentException("\"" + c.swatchHex().trim() + "\" is not a colour like #9B111E");
                }
            }
            ColorFamily family = ColorFamily.parse(c.colorFamily());
            v.setOptionColor(color);
            v.setOptionSize(size);
            v.setSwatchHex(color == null ? null : hex);
            v.setColorFamily(color == null || family == null ? null : family.name());
            if (color == null && size == null) {
                v.setOptionLabel(null);
            }
            changed++;
        }
        variants.saveAll(live.values());
        productOptions.sync(productId);
        markReviewed(product);
        return changed;
    }

    /** The product's options are not colours or sizes: leave its variants as they are and take it off the list. */
    @Transactional
    public void leave(UUID productId) {
        Product product = products.findById(productId).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such product."));
        markReviewed(product);
    }

    private void markReviewed(Product product) {
        product.setOptionsReviewedAt(Instant.now());
        products.save(product); // also moves updatedAt, so an editor opened before this reloads rather than undoing it
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
    }

    /** Products with two or more variants (not kits) where some variant has neither a colour nor a size. */
    private Map<Product, List<ProductVariant>> waiting() {
        Map<Product, List<ProductVariant>> byProduct = new LinkedHashMap<>();
        for (ProductVariant v : variants.findForOptionsReview()) {
            byProduct.computeIfAbsent(v.getProduct(), k -> new ArrayList<>()).add(v);
        }
        byProduct.entrySet().removeIf(e -> e.getValue().size() < 2
                || e.getValue().stream().allMatch(v -> v.getOptionColor() != null || v.getOptionSize() != null));
        byProduct.values().forEach(list -> list.sort(Comparator.comparingInt(ProductVariant::getSortOrder)));
        return byProduct;
    }

    /** A variant's suggestion: what it already has, else what its name says (a size, a known colour), else nothing. */
    static VariantRow suggest(ProductVariant v) {
        String option = v.shortOption();
        if (v.getOptionColor() != null || v.getOptionSize() != null) {
            return new VariantRow(v.getId(), v.getVariantName(), option, v.getOptionColor(), v.getOptionSize(),
                    v.getSwatchHex(), v.getColorFamily(), true);
        }
        String color = null;
        String size = null;
        String hex = null;
        String family = null;
        if (option != null) {
            // Sizes first: a whole part ("50ml", "Large"), or one written inside a part ("Clear 100g" → 100g + Clear).
            List<String> rest = new ArrayList<>();
            for (String token : SPLIT.split(option.trim())) {
                String t = ProductOptionsService.clean(token);
                if (t == null) continue;
                if (size == null && SIZE.matcher(t).matches()) {
                    size = t;
                    continue;
                }
                var m = size == null ? SIZE_PART.matcher(t) : null;
                if (m != null && m.matches()) {
                    size = m.group(1) != null ? m.group(1) : m.group(4);
                    rest.add(m.group(1) != null ? m.group(2) : m.group(3));
                    continue;
                }
                rest.add(t);
            }
            // The colour from the last part that names one: in "Chrome Mirror Gel Polish - Red" the first part is the
            // product's own name, whose "Chrome" is not this variant's colour.
            for (int i = rest.size() - 1; i >= 0 && color == null; i--) {
                SwatchSuggester.Shade shade = SwatchSuggester.knownShade(rest.get(i));
                if (shade != null) {
                    color = rest.get(i);
                    hex = shade.hex();
                    family = shade.family() == null ? null : shade.family().name();
                }
            }
        }
        return new VariantRow(v.getId(), v.getVariantName(), option, color, size, hex, family, false);
    }
}
