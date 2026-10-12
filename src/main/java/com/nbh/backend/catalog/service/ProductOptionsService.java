package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductOptionValue;
import com.nbh.backend.catalog.entity.ProductOptionValue.Kind;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductOptionValueRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.variantgen.VariantCombos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Keeps a product's colour and size list ({@link ProductOptionValue}, V63) in step with its variants. Call it after
 * anything that adds, removes or re-labels variants: the product editor's save, duplicate, the variant generator.
 *
 * <ul>
 *   <li>A value is listed while at least one of the product's variants (not removed; hidden ones count, so an
 *       approve-first run keeps its options) has it. Matching ignores case and surrounding spaces; the variant is then
 *       spelled as the list spells it, so "red" next to "Red" cannot become a second dot.</li>
 *   <li>Order is kept: values already listed stay where they are, new ones join the end in the order of the first
 *       variant that has them, and positions are renumbered 0.. with no gaps.</li>
 *   <li>A colour's dot and family: the list keeps what it has; when it has none, it takes the first variant's. A variant
 *       with no dot or family of its own takes the list's, so every variant of one colour shows the same dot.</li>
 *   <li>Each variant's {@code sizePosition} follows its size's place in the list, and a variant with a colour or size
 *       gets the short option label that goes with them ("Ruby Red / 50ml").</li>
 * </ul>
 */
@Service
public class ProductOptionsService {

    private final ProductOptionValueRepository options;
    private final ProductVariantRepository variants;

    public ProductOptionsService(ProductOptionValueRepository options, ProductVariantRepository variants) {
        this.options = options;
        this.variants = variants;
    }

    @Transactional
    public void sync(UUID productId) {
        List<ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        List<ProductOptionValue> stored = options.findByProductIdOrderByKindAscPositionAsc(productId);
        List<ProductOptionValue> toDelete = new ArrayList<>();

        Map<String, ProductOptionValue> colors = syncKind(productId, Kind.COLOR, stored, live,
                ProductVariant::getOptionColor, toDelete);
        Map<String, ProductOptionValue> sizes = syncKind(productId, Kind.SIZE, stored, live,
                ProductVariant::getOptionSize, toDelete);

        for (ProductVariant v : live) {
            ProductOptionValue color = colors.get(key(v.getOptionColor()));
            if (color == null) {
                v.setOptionColor(null);
            } else {
                v.setOptionColor(color.getValue());
                if (color.getSwatchHex() == null && v.getSwatchHex() != null) {
                    color.setSwatchHex(v.getSwatchHex());
                }
                if (color.getColorFamily() == null && v.getColorFamily() != null) {
                    color.setColorFamily(v.getColorFamily());
                }
            }
            ProductOptionValue size = sizes.get(key(v.getOptionSize()));
            v.setOptionSize(size == null ? null : size.getValue());
            v.setSizePosition(size == null ? null : size.getPosition());
            if (v.getOptionColor() != null || v.getOptionSize() != null) {
                // The short option the picker, cards and orders show: "Ruby Red / 50ml", "Ruby Red" or "50ml".
                v.setOptionLabel(new VariantCombos.Combo(v.getOptionColor(), v.getOptionSize()).label());
            }
        }
        // Second pass, once every colour row has taken its dot from the first variant that had one.
        for (ProductVariant v : live) {
            ProductOptionValue color = colors.get(key(v.getOptionColor()));
            if (color != null) {
                if (v.getSwatchHex() == null) v.setSwatchHex(color.getSwatchHex());
                if (v.getColorFamily() == null) v.setColorFamily(color.getColorFamily());
            }
        }

        options.deleteAll(toDelete);
        options.flush();
        List<ProductOptionValue> keep = new ArrayList<>(colors.values());
        keep.addAll(sizes.values());
        options.saveAll(keep);
        variants.saveAll(live);
    }

    /**
     * A duplicated product starts with the source's list (order, dots, families, size label text), so the copy's
     * picker reads the same; {@link #sync} then trims it to what the copy's variants have. A size's photo is not
     * carried over: it is one of the source's photos.
     */
    @Transactional
    public void copyList(UUID fromProductId, UUID toProductId) {
        List<ProductOptionValue> copies = new ArrayList<>();
        for (ProductOptionValue row : options.findByProductIdOrderByKindAscPositionAsc(fromProductId)) {
            ProductOptionValue c = new ProductOptionValue();
            c.setProductId(toProductId);
            c.setKind(row.getKind());
            c.setValue(row.getValue());
            c.setPosition(row.getPosition());
            c.setSwatchHex(row.getSwatchHex());
            c.setColorFamily(row.getColorFamily());
            c.setSizeLabelText(row.getSizeLabelText());
            copies.add(c);
        }
        options.saveAll(copies);
    }

    /** The rows of one kind after the sync, keyed by {@link #key}, in list order; rows no variant uses go in {@code toDelete}. */
    private Map<String, ProductOptionValue> syncKind(UUID productId, Kind kind, List<ProductOptionValue> stored,
                                                     List<ProductVariant> live, Function<ProductVariant, String> valueOf,
                                                     List<ProductOptionValue> toDelete) {
        Map<String, String> used = new LinkedHashMap<>(); // key -> first spelling, in variant order
        for (ProductVariant v : live) {
            String value = clean(valueOf.apply(v));
            if (value != null) {
                used.putIfAbsent(key(value), value);
            }
        }
        Map<String, ProductOptionValue> rows = new LinkedHashMap<>();
        for (ProductOptionValue row : stored) {
            if (row.getKind() != kind) {
                continue;
            }
            String k = key(row.getValue());
            if (used.containsKey(k) && !rows.containsKey(k)) {
                rows.put(k, row);
            } else {
                toDelete.add(row);
            }
        }
        for (Map.Entry<String, String> e : used.entrySet()) {
            rows.computeIfAbsent(e.getKey(), k -> {
                ProductOptionValue row = new ProductOptionValue();
                row.setProductId(productId);
                row.setKind(kind);
                row.setValue(e.getValue());
                return row;
            });
        }
        int position = 0;
        for (ProductOptionValue row : rows.values()) {
            row.setPosition(position++);
        }
        return rows;
    }

    /** Trimmed, inner spaces collapsed, cut to the column's 80; null when blank. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > 80 ? t.substring(0, 80).trim() : t;
    }

    /** What two spellings of the same value have in common. */
    static String key(String raw) {
        String c = clean(raw);
        return c == null ? null : c.toLowerCase(Locale.ROOT);
    }
}
