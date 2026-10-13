package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.ColorFamily;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductOptionValue;
import com.nbh.backend.catalog.entity.ProductOptionValue.Kind;
import com.nbh.backend.catalog.repository.ProductOptionValueRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Colours and sizes the shop already uses, so staff don't type them again (owner, 2026-10-13: "reuse the colors used
 * from one product's variants"): every colour across the real products with the dot and family it is usually given,
 * and each product's own colour and size list to copy into the variant wizard. Read from the products' colour/size
 * lists (V63), so it is always what the shop has now; nothing separate to keep up to date.
 */
@Service
public class ColourLibraryService {

    /** A colour the shop uses: its usual spelling, dot and family, and on how many products. */
    public record LibraryColour(String value, String swatchHex, String colorFamily, int products) {}

    public record SetColour(String value, String swatchHex, String colorFamily) {}

    public record SetSize(String value, String labelText) {}

    /** One product's colours and sizes, in its own order, to copy into the wizard. */
    public record OptionSet(UUID productId, String productName, List<SetColour> colors, List<SetSize> sizes) {}

    private final ProductOptionValueRepository options;
    private final ProductRepository products;

    public ColourLibraryService(ProductOptionValueRepository options, ProductRepository products) {
        this.options = options;
        this.products = products;
    }

    /** The shop's colours matching {@code q} (any part of the name; blank = all), most used first. */
    @Transactional(readOnly = true)
    public List<LibraryColour> colours(String q, int limit) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<String, List<ProductOptionValue>> byName = options.findOfLiveProducts().stream()
                .filter(o -> o.getKind() == Kind.COLOR)
                .filter(o -> needle.isEmpty() || o.getValue().toLowerCase(Locale.ROOT).contains(needle))
                .collect(Collectors.groupingBy(o -> o.getValue().trim().toLowerCase(Locale.ROOT)));
        return byName.values().stream()
                .map(ColourLibraryService::merge)
                .sorted(Comparator.comparingInt(LibraryColour::products).reversed()
                        .thenComparing(LibraryColour::value, String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(1, Math.min(limit, 200)))
                .toList();
    }

    /** The shop's own dot and family for a colour name, exactly (any case), or null when no product uses it. */
    @Transactional(readOnly = true)
    public LibraryColour known(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        List<ProductOptionValue> rows = options.findOfLiveProducts().stream()
                .filter(o -> o.getKind() == Kind.COLOR && o.getValue().trim().toLowerCase(Locale.ROOT).equals(key))
                .toList();
        return rows.isEmpty() ? null : merge(rows);
    }

    /** Products with colours or sizes whose name matches {@code q}, alphabetically, each with its lists. */
    @Transactional(readOnly = true)
    public List<OptionSet> sets(String q, int limit) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<UUID, List<ProductOptionValue>> byProduct = options.findOfLiveProducts().stream()
                .collect(Collectors.groupingBy(ProductOptionValue::getProductId));
        Map<UUID, Product> named = products.findAllById(byProduct.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return byProduct.entrySet().stream()
                .filter(e -> named.containsKey(e.getKey()))
                .filter(e -> needle.isEmpty()
                        || Objects.toString(named.get(e.getKey()).getName(), "").toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(e -> Objects.toString(named.get(e.getKey()).getName(), ""),
                        String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(1, Math.min(limit, 100)))
                .map(e -> {
                    List<ProductOptionValue> rows = e.getValue().stream()
                            .sorted(Comparator.comparingInt(ProductOptionValue::getPosition)).toList();
                    return new OptionSet(e.getKey(), named.get(e.getKey()).getName(),
                            rows.stream().filter(o -> o.getKind() == Kind.COLOR)
                                    .map(o -> new SetColour(o.getValue(), o.getSwatchHex(), family(o))).toList(),
                            rows.stream().filter(o -> o.getKind() == Kind.SIZE)
                                    .map(o -> new SetSize(o.getValue(), o.getSizeLabelText())).toList());
                })
                .toList();
    }

    /** One colour as the shop uses it on several products: the commonest spelling, dot and family. */
    private static LibraryColour merge(List<ProductOptionValue> rows) {
        String value = commonest(rows.stream().map(o -> o.getValue().trim()).toList());
        String hex = commonest(rows.stream().map(ProductOptionValue::getSwatchHex).filter(Objects::nonNull).toList());
        String stored = commonest(rows.stream().map(ProductOptionValue::getColorFamily).filter(Objects::nonNull).toList());
        ColorFamily f = stored != null ? ColorFamily.ofStored(stored) : ColorFamily.fromHex(hex);
        int productCount = (int) rows.stream().map(ProductOptionValue::getProductId).distinct().count();
        return new LibraryColour(value, hex, f == null ? null : f.name(), productCount);
    }

    private static String family(ProductOptionValue o) {
        ColorFamily f = o.effectiveColorFamily();
        return f == null ? null : f.name();
    }

    private static String commonest(List<String> values) {
        return values.stream().collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
}
