package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ColorFamily;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductOptionValue;
import com.nbh.backend.catalog.entity.ProductOptionValue.Kind;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductOptionValueRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.variantgen.VariantCombos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final ProductRepository products;
    private final ProductImageRepository images;

    public ProductOptionsService(ProductOptionValueRepository options, ProductVariantRepository variants,
                                 ProductRepository products, ProductImageRepository images) {
        this.options = options;
        this.variants = variants;
        this.products = products;
        this.images = images;
    }

    /** One colour or size as the staff screens see it, with how many of the product's variants have it. */
    public record OptionDto(UUID id, Kind kind, String value, int position, String swatchHex, String colorFamily,
                            String effectiveColorFamily, String sizeLabelText, UUID sizePhotoImageId, int variantCount) {}

    /**
     * A change to one colour or size. Every field is optional: null = leave it, "" = clear it (except {@code value},
     * which can only be renamed). {@code swatchHex} and {@code colorFamily} are for colours, {@code sizeLabelText} and
     * {@code sizePhotoImageId} for sizes.
     */
    public record OptionChange(String value, String swatchHex, String colorFamily, String sizeLabelText,
                               String sizePhotoImageId) {}

    /** The product's colours then its sizes, each in shop order. */
    @Transactional(readOnly = true)
    public List<OptionDto> list(UUID productId) {
        requireProduct(productId);
        List<ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        return options.findByProductIdOrderByKindAscPositionAsc(productId).stream()
                .map(o -> toDto(o, live))
                .toList();
    }

    /**
     * Staff's change to one colour or size, applied to every variant that has it, so a colour is renamed, re-dotted or
     * re-filed in one go across all its sizes. A rename also renames each variant whose name is still the one the
     * generator gave it ("Lipstick – Rubby Red / 50ml"); a name staff wrote themselves is left alone. Renaming onto a
     * value the product already has is refused rather than merging two options without asking.
     */
    @Transactional
    public OptionDto update(UUID productId, UUID optionId, OptionChange change) {
        Product product = requireProduct(productId);
        ProductOptionValue row = options.findById(optionId).filter(o -> o.getProductId().equals(productId))
                .orElseThrow(() -> new NotFoundException("That colour or size is not on this product."));
        boolean color = row.getKind() == Kind.COLOR;
        if (!color && (change.swatchHex() != null || change.colorFamily() != null)) {
            throw new IllegalArgumentException("A size has no colour dot or colour family.");
        }
        if (color && (change.sizeLabelText() != null || change.sizePhotoImageId() != null)) {
            throw new IllegalArgumentException("Label text and a size photo belong to a size, not a colour.");
        }
        List<ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        Function<ProductVariant, String> valueOf = color ? ProductVariant::getOptionColor : ProductVariant::getOptionSize;
        String oldKey = key(row.getValue());
        List<ProductVariant> having = live.stream().filter(v -> oldKey.equals(key(valueOf.apply(v)))).toList();

        if (change.value() != null) {
            String renamed = clean(change.value());
            if (renamed == null) {
                throw new IllegalArgumentException("A " + (color ? "colour" : "size") + " needs a name.");
            }
            boolean taken = options.findByProductIdOrderByKindAscPositionAsc(productId).stream()
                    .anyMatch(o -> o.getKind() == row.getKind() && !o.getId().equals(optionId)
                            && key(o.getValue()).equals(key(renamed)));
            if (taken) {
                throw new ConflictException("This product already has the " + (color ? "colour" : "size") + " \""
                        + renamed + "\". Rename it to something else, or move its variants to that one in the editor.");
            }
            for (ProductVariant v : having) {
                String oldLabel = v.getOptionLabel();
                if (color) {
                    v.setOptionColor(renamed);
                } else {
                    v.setOptionSize(renamed);
                }
                String newLabel = new VariantCombos.Combo(v.getOptionColor(), v.getOptionSize()).label();
                if (oldLabel != null && v.getVariantName() != null
                        && v.getVariantName().equals(VariantCombos.variantName(product.getName(), oldLabel))) {
                    v.setVariantName(VariantCombos.variantName(product.getName(), newLabel));
                }
                v.setOptionLabel(newLabel);
            }
            row.setValue(renamed);
        }
        if (change.swatchHex() != null) {
            String hex = null;
            if (!change.swatchHex().isBlank()) {
                hex = VariantCombos.normaliseHex(change.swatchHex());
                if (hex == null) {
                    throw new IllegalArgumentException("\"" + change.swatchHex().trim() + "\" is not a colour like #9B111E");
                }
            }
            row.setSwatchHex(hex);
            for (ProductVariant v : having) {
                v.setSwatchHex(hex);
            }
        }
        if (change.colorFamily() != null) {
            ColorFamily family = ColorFamily.parse(change.colorFamily());
            String stored = family == null ? null : family.name();
            row.setColorFamily(stored);
            for (ProductVariant v : having) {
                v.setColorFamily(stored);
            }
        }
        if (change.sizeLabelText() != null) {
            String text = change.sizeLabelText().replaceAll("\\s+", " ").trim();
            if (text.length() > 60) {
                throw new IllegalArgumentException("Label text can be at most 60 characters.");
            }
            row.setSizeLabelText(text.isEmpty() ? null : text);
        }
        if (change.sizePhotoImageId() != null) {
            UUID photo = null;
            if (!change.sizePhotoImageId().isBlank()) {
                try {
                    photo = UUID.fromString(change.sizePhotoImageId().trim());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("That is not a photo of this product.");
                }
                UUID id = photo;
                images.findById(id).filter(i -> i.getProduct().getId().equals(productId))
                        .orElseThrow(() -> new IllegalArgumentException("That is not a photo of this product."));
            }
            row.setSizePhotoImageId(photo);
        }
        options.save(row);
        variants.saveAll(having);
        sync(productId);
        products.touch(productId, java.time.Instant.now());
        return toDto(options.findById(optionId).orElseThrow(),
                variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId));
    }

    /**
     * Puts a product's colours (or sizes) in the order given. {@code ids} must be exactly that kind's list, so a screen
     * that is out of date cannot drop or invent an option.
     */
    @Transactional
    public List<OptionDto> reorder(UUID productId, Kind kind, List<UUID> ids) {
        requireProduct(productId);
        if (kind == null || ids == null) {
            throw new IllegalArgumentException("Say which list (COLOR or SIZE) and give its options in order.");
        }
        List<ProductOptionValue> rows = options.findByProductIdOrderByKindAscPositionAsc(productId).stream()
                .filter(o -> o.getKind() == kind).toList();
        Set<UUID> given = new HashSet<>(ids);
        Set<UUID> stored = new HashSet<>(rows.stream().map(ProductOptionValue::getId).toList());
        if (given.size() != ids.size() || !given.equals(stored)) {
            throw new IllegalArgumentException("The list has changed since it was loaded. Reload and try again.");
        }
        Map<UUID, ProductOptionValue> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i);
        }
        options.saveAll(rows);
        options.flush();
        sync(productId);
        products.touch(productId, java.time.Instant.now());
        return list(productId);
    }

    private Product requireProduct(UUID productId) {
        return products.findById(productId).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such product."));
    }

    private static OptionDto toDto(ProductOptionValue o, List<ProductVariant> live) {
        boolean color = o.getKind() == Kind.COLOR;
        String k = key(o.getValue());
        int count = (int) live.stream()
                .filter(v -> k.equals(key(color ? v.getOptionColor() : v.getOptionSize()))).count();
        ColorFamily effective = color ? o.effectiveColorFamily() : null;
        return new OptionDto(o.getId(), o.getKind(), o.getValue(), o.getPosition(), o.getSwatchHex(), o.getColorFamily(),
                effective == null ? null : effective.name(), o.getSizeLabelText(), o.getSizePhotoImageId(), count);
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
