package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.ColorFamily;
import com.nbh.backend.catalog.service.ProductOptionsService;
import com.nbh.backend.variantgen.VariantCombos;
import com.nbh.backend.variantjobs.VariantJobRequest.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.*;

/**
 * Works out exactly what a wizard request would make, and what is wrong or unusual about it, without touching anything
 * (VARIANTS_PLAN.md 3.4, 3.7). The preview shows its answer; Generate refuses while it has errors. Pure: everything it
 * needs from the database comes in a {@link Context}.
 */
public final class VariantJobValidator {

    public static final int MAX_NEW = VariantCombos.MAX_VARIANTS;
    private static final BigDecimal MAX_PRICE = new BigDecimal("10000000");
    private static final BigDecimal MAX_STOCK = new BigDecimal("1000000");
    /** Descriptions per Gemini call (VariantPlanner's chunk). */
    static final int DESCRIPTION_CHUNK = 40;

    /** What the database says about the product. */
    public record Context(String productName, Set<String> existingCombos, boolean basePhoto, Set<UUID> productImageIds,
                          boolean wholeUnits) {}

    /** One combination: made, skipped because it exists, or left out. */
    public record Row(String color, String size, String label, String name, BigDecimal price, String priceFrom,
                      BigDecimal openingStock, BigDecimal unitCost, boolean included, boolean exists) {
        public boolean toMake() {
            return included && !exists;
        }
    }

    public record Estimate(int variants, int photoCalls, int descriptionCalls, int minutes) {}

    public record Plan(List<Row> rows, List<String> errors, List<String> warnings, List<String> summary, Estimate estimate) {
        public List<Row> toMake() {
            return rows.stream().filter(Row::toMake).toList();
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private VariantJobValidator() {}

    public static Plan plan(VariantJobRequest r, Context ctx) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> summary = new ArrayList<>();

        List<String> colors = names(r.colors() == null ? List.of() : r.colors().stream().map(ColorIn::name).toList(),
                "colour", errors);
        List<String> sizes = names(r.sizes() == null ? List.of() : r.sizes().stream().map(SizeIn::name).toList(),
                "size", errors);
        if (colors.isEmpty() && sizes.isEmpty() && errors.isEmpty()) {
            errors.add("Give at least one colour or one size.");
        }
        checkColors(r, errors, warnings);
        checkSizes(r, ctx, errors);

        Map<String, String> colorByKey = byKey(colors);
        Map<String, String> sizeByKey = byKey(sizes);

        // Which combinations are left out, by the "only some colours" shortcut or by a cell.
        Set<String> excluded = new LinkedHashSet<>();
        Map<String, List<String>> onlyIn = new LinkedHashMap<>();
        for (SizeAvailability a : nn(r.sizeAvailability())) {
            String size = known(a.size(), sizeByKey, "size", errors);
            if (size == null) continue;
            List<String> only = new ArrayList<>();
            for (String c : nn(a.onlyColors())) {
                String color = known(c, colorByKey, "colour", errors);
                if (color != null) only.add(color);
            }
            if (only.isEmpty()) {
                errors.add("\"" + size + "\" must come in at least one colour; remove the size instead.");
                continue;
            }
            onlyIn.put(size, only);
            for (String color : colors) {
                if (!only.contains(color)) excluded.add(cellKey(color, size));
            }
        }
        Map<String, CellIn> cells = new HashMap<>();
        for (CellIn c : nn(r.cells())) {
            String color = colors.isEmpty() ? null : known(c.color(), colorByKey, "colour", errors);
            String size = sizes.isEmpty() ? null : known(c.size(), sizeByKey, "size", errors);
            if ((!colors.isEmpty() && color == null) || (!sizes.isEmpty() && size == null)) continue;
            cells.put(cellKey(color, size), c);
            if (Boolean.FALSE.equals(c.include())) excluded.add(cellKey(color, size));
        }

        Pricing pricing = r.pricing();
        Mode mode = pricing == null ? null : pricing.mode();
        if (mode == null) {
            errors.add("Choose how prices work: the same for all, going up or down with size, per size, or each one on its own.");
        }
        Map<String, BigDecimal> adjustments = new LinkedHashMap<>();
        Map<String, BigDecimal> overrides = new LinkedHashMap<>();
        Map<String, String> overrideLabels = new HashMap<>();
        Map<String, BigDecimal> sizePrices = new HashMap<>();
        if (pricing != null && mode != null) {
            checkRule(pricing, mode, sizes, sizeByKey, sizePrices, errors);
            if (mode != Mode.PER_CELL) {
                for (ColorAdjustment a : nn(pricing.colorAdjustments())) {
                    String color = known(a.color(), colorByKey, "colour", errors);
                    if (color == null) continue;
                    if (a.amount() == null || a.amount().signum() == 0) {
                        errors.add("Say how much more or less " + color + " costs.");
                        continue;
                    }
                    adjustments.put(color, a.amount());
                }
                for (CellOverride o : nn(pricing.cellOverrides())) {
                    String color = colors.isEmpty() ? null : known(o.color(), colorByKey, "colour", errors);
                    String size = sizes.isEmpty() ? null : known(o.size(), sizeByKey, "size", errors);
                    if ((!colors.isEmpty() && color == null) || (!sizes.isEmpty() && size == null)) continue;
                    if (o.price() == null) {
                        errors.add("Give " + label(color, size) + " its own price, or remove that exception.");
                        continue;
                    }
                    overrides.put(cellKey(color, size), o.price());
                    overrideLabels.put(cellKey(color, size), label(color, size));
                }
            }
        }

        // Every combination, in the order the shop and the generator use: colour by colour, sizes in order.
        List<Row> rows = new ArrayList<>();
        List<String> cs = colors.isEmpty() ? Collections.singletonList(null) : colors;
        List<String> ss = sizes.isEmpty() ? Collections.singletonList(null) : sizes;
        for (String color : cs) {
            for (int si = 0; si < ss.size(); si++) {
                String size = ss.get(si);
                String key = cellKey(color, size);
                CellIn cell = cells.get(key);
                boolean included = !excluded.contains(key);
                boolean exists = ctx.existingCombos().contains(key);
                BigDecimal price = null;
                String from = null;
                if (mode != null && included) {
                    if (mode == Mode.PER_CELL) {
                        price = cell == null ? null : cell.price();
                        from = "cell";
                    } else if (overrides.containsKey(key)) {
                        price = overrides.get(key);
                        from = "own price";
                    } else {
                        BigDecimal base = rulePrice(mode, pricing, si, size, sizePrices);
                        BigDecimal adj = color == null ? null : adjustments.get(color);
                        price = base == null ? null : (adj == null ? base : base.add(adj));
                        from = adj == null ? "rule" : "colour";
                    }
                    if (price != null) price = price.setScale(2, RoundingMode.HALF_UP);
                    if (mode != Mode.PER_CELL && cell != null && cell.price() != null && price != null
                            && cell.price().compareTo(price) != 0) {
                        errors.add(label(color, size) + ": the screen says " + money(cell.price()) + " but the prices work out to "
                                + money(price) + ". Reload the wizard and check the prices.");
                    }
                }
                BigDecimal stock = cell == null ? null : cell.openingStock();
                BigDecimal cost = cell == null ? null : cell.unitCost();
                rows.add(new Row(color, size, label(color, size),
                        VariantCombos.variantName(ctx.productName(), label(color, size)), price, from,
                        stock, cost, included, exists));
            }
        }
        if (Boolean.TRUE.equals(r.everyCombination()) && !colors.isEmpty() && !sizes.isEmpty() && !excluded.isEmpty()) {
            errors.add("\"Every size comes in every colour\" is ticked, but some combinations are left out. "
                    + "Untick it, or put them back.");
        }

        List<Row> toMake = rows.stream().filter(Row::toMake).toList();
        for (Row row : toMake) {
            if (mode != null && row.price() == null) {
                errors.add(row.label() + " has no price.");
            } else if (row.price() != null && row.price().signum() <= 0) {
                errors.add(row.label() + " would cost " + money(row.price()) + ". A price must be more than 0.");
            } else if (row.price() != null && row.price().compareTo(MAX_PRICE) > 0) {
                errors.add(row.label() + " would cost " + money(row.price()) + ", which is too much.");
            }
            if (row.openingStock() != null) {
                if (row.openingStock().signum() < 0) {
                    errors.add(row.label() + ": opening stock can't be negative.");
                } else if (row.openingStock().compareTo(MAX_STOCK) > 0) {
                    errors.add(row.label() + ": opening stock is too large.");
                } else if (ctx.wholeUnits() && row.openingStock().stripTrailingZeros().scale() > 0) {
                    errors.add(row.label() + ": opening stock must be a whole number.");
                } else if (row.openingStock().signum() > 0 && row.unitCost() == null) {
                    warnings.add(row.label() + ": opening stock without a cost. Stock value reports will count it at 0.");
                }
            }
            if (row.unitCost() != null && row.unitCost().signum() < 0) {
                errors.add(row.label() + ": cost can't be negative.");
            }
        }
        if (errors.isEmpty() && toMake.isEmpty()) {
            errors.add("There is nothing new to make: every combination is left out or the product already has it.");
        }
        if (toMake.size() > MAX_NEW) {
            errors.add("That makes " + toMake.size() + " variants; the most in one go is " + MAX_NEW
                    + ". Split it into two, for example by size.");
        }
        List<String> existing = rows.stream().filter(x -> x.included() && x.exists()).map(Row::label).toList();
        if (!existing.isEmpty()) {
            warnings.add("Already on this product, will be skipped: " + String.join(", ", existing) + ".");
        }
        priceWarnings(toMake, colors, sizes, warnings);

        // The summary staff confirm: the rule, then each exception in plain words.
        if (mode != null && pricing != null) {
            summary.add(ruleSummary(mode, pricing, sizes, sizePrices));
            adjustments.forEach((c, a) -> summary.add(c + ": " + (a.signum() > 0 ? "+" : "−") + money(a.abs()) + " in every size"));
            overrides.forEach((k, p) -> summary.add(overrideLabels.get(k) + ": " + money(p)));
        }
        onlyIn.forEach((s, only) -> summary.add(s + ": only " + String.join(", ", only)));
        List<String> leftOut = rows.stream().filter(x -> !x.included()
                && !onlyIn.containsKey(x.size())).map(Row::label).toList();
        if (!leftOut.isEmpty()) {
            summary.add("Not made: " + String.join(", ", leftOut));
        }

        return new Plan(rows, errors, warnings, summary, estimate(toMake, r));
    }

    // ---------------------------------------------------------------- checks

    private static List<String> names(List<String> raw, String what, List<String> errors) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String n : raw) {
            String c = ProductOptionsService.clean(n);
            if (c == null) {
                errors.add("A " + what + " has no name.");
                continue;
            }
            if (n.trim().length() > 60) {
                errors.add("\"" + c + "\" is too long for a " + what + " name (60 characters at most).");
                continue;
            }
            if (!seen.add(c.toLowerCase(Locale.ROOT))) {
                errors.add("The " + what + " \"" + c + "\" is listed twice.");
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private static void checkColors(VariantJobRequest r, List<String> errors, List<String> warnings) {
        List<double[]> labs = new ArrayList<>();
        List<String> labNames = new ArrayList<>();
        for (ColorIn c : nn(r.colors())) {
            String name = ProductOptionsService.clean(c.name());
            if (c.hex() != null && !c.hex().isBlank()) {
                String hex = VariantCombos.normaliseHex(c.hex());
                if (hex == null) {
                    errors.add(name + ": \"" + c.hex().trim() + "\" is not a colour like #9B111E.");
                } else {
                    double[] lab = lab(hex);
                    for (int i = 0; i < labs.size(); i++) {
                        if (deltaE(lab, labs.get(i)) < 5) {
                            warnings.add(labNames.get(i) + " and " + name + " have nearly the same dot. Customers may not tell them apart.");
                        }
                    }
                    labs.add(lab);
                    labNames.add(name);
                }
            }
            if (c.family() != null && !c.family().isBlank()) {
                try {
                    ColorFamily.parse(c.family());
                } catch (IllegalArgumentException e) {
                    errors.add(name + ": " + e.getMessage() + ".");
                }
            }
        }
    }

    private static void checkSizes(VariantJobRequest r, Context ctx, List<String> errors) {
        boolean anySizePhoto = false;
        for (SizeIn s : nn(r.sizes())) {
            String name = ProductOptionsService.clean(s.name());
            if (s.labelText() != null && s.labelText().trim().length() > 60) {
                errors.add(name + ": label text can be at most 60 characters.");
            }
            if (s.photoImageId() != null) {
                if (!ctx.productImageIds().contains(s.photoImageId())) {
                    errors.add(name + ": that photo is not one of this product's photos.");
                } else {
                    anySizePhoto = true;
                }
            }
        }
        if (!ctx.basePhoto() && !anySizePhoto) {
            errors.add("The product has no photo to make the variants' photos from. Add one to the product first.");
        }
    }

    private static void checkRule(Pricing p, Mode mode, List<String> sizes, Map<String, String> sizeByKey,
                                  Map<String, BigDecimal> sizePrices, List<String> errors) {
        switch (mode) {
            case SAME -> {
                if (p.price() == null) errors.add("Give the price for all of them.");
            }
            case STEP_UP, STEP_DOWN -> {
                if (sizes.size() < 2) errors.add("Prices that go up or down with size need at least two sizes.");
                if (p.start() == null) errors.add("Give the price of the " + (mode == Mode.STEP_UP ? "smallest" : "first") + " size.");
                if (p.step() == null || p.step().signum() < 0) errors.add("Give how much the price changes from one size to the next.");
            }
            case PER_SIZE -> {
                Map<String, BigDecimal> given = new HashMap<>();
                if (p.sizePrices() != null) {
                    p.sizePrices().forEach((k, v) -> {
                        String size = known(k, sizeByKey, "size", errors);
                        if (size != null) given.put(size, v);
                    });
                }
                for (String s : sizes) {
                    if (given.get(s) == null) errors.add("Give the price for " + s + ".");
                }
                sizePrices.putAll(given);
                if (sizes.isEmpty()) errors.add("Prices per size need sizes.");
            }
            case PER_CELL -> { }
        }
    }

    private static BigDecimal rulePrice(Mode mode, Pricing p, int sizeIndex, String size, Map<String, BigDecimal> sizePrices) {
        return switch (mode) {
            case SAME -> p.price();
            case STEP_UP -> p.start() == null || p.step() == null ? null
                    : p.start().add(p.step().multiply(BigDecimal.valueOf(sizeIndex)));
            case STEP_DOWN -> p.start() == null || p.step() == null ? null
                    : p.start().subtract(p.step().multiply(BigDecimal.valueOf(sizeIndex)));
            case PER_SIZE -> size == null ? null : sizePrices.get(size);
            case PER_CELL -> null;
        };
    }

    /** A bigger size cheaper than a smaller one of the same colour, and prices far from the rest of their row or column. */
    private static void priceWarnings(List<Row> rows, List<String> colors, List<String> sizes, List<String> warnings) {
        Map<String, List<Row>> byColor = new LinkedHashMap<>();
        Map<String, List<Row>> bySize = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.price() == null) continue;
            byColor.computeIfAbsent(String.valueOf(r.color()), k -> new ArrayList<>()).add(r);
            bySize.computeIfAbsent(String.valueOf(r.size()), k -> new ArrayList<>()).add(r);
        }
        if (!sizes.isEmpty()) {
            for (List<Row> line : byColor.values()) {
                List<Row> ordered = line.stream().sorted(Comparator.comparingInt(x -> sizes.indexOf(x.size()))).toList();
                for (int i = 1; i < ordered.size(); i++) {
                    Row smaller = ordered.get(i - 1);
                    Row bigger = ordered.get(i);
                    if (bigger.price().compareTo(smaller.price()) < 0) {
                        warnings.add(bigger.label() + " (" + money(bigger.price()) + ") is cheaper than " + smaller.label()
                                + " (" + money(smaller.price()) + "). Check this is right.");
                    }
                }
            }
        }
        Set<String> flagged = new HashSet<>();
        for (Collection<List<Row>> groups : List.of(byColor.values(), bySize.values())) {
            for (List<Row> group : groups) {
                if (group.size() < 3) continue;
                BigDecimal median = median(group.stream().map(Row::price).toList());
                for (Row r : group) {
                    boolean far = r.price().compareTo(median.multiply(BigDecimal.valueOf(3))) > 0
                            || r.price().multiply(BigDecimal.valueOf(3)).compareTo(median) < 0;
                    if (far && flagged.add(r.label())) {
                        warnings.add(r.label() + " costs " + money(r.price()) + ", far from the others around "
                                + money(median) + ". Is that a typing mistake?");
                    }
                }
            }
        }
    }

    private static Estimate estimate(List<Row> toMake, VariantJobRequest r) {
        Map<String, SizeIn> sizeInfo = new HashMap<>();
        for (SizeIn s : nn(r.sizes())) {
            String c = ProductOptionsService.clean(s.name());
            if (c != null) sizeInfo.put(c, s);
        }
        PhotoPlan photos = PhotoPlan.of(toMake.stream().map(x -> new PhotoPlan.Want(x.color(), x.size())).toList(),
                size -> {
                    SizeIn s = sizeInfo.get(size);
                    return s == null ? null : s.photoImageId();
                },
                size -> {
                    SizeIn s = sizeInfo.get(size);
                    return s == null || s.labelText() == null || s.labelText().isBlank() ? null : s.labelText().trim();
                });
        int descriptionCalls = (toMake.size() + DESCRIPTION_CHUNK - 1) / DESCRIPTION_CHUNK;
        int seconds = photos.aiCalls() * 15 + descriptionCalls * 20 + toMake.size();
        return new Estimate(toMake.size(), photos.aiCalls(), descriptionCalls, Math.max(1, (seconds + 59) / 60));
    }

    // ---------------------------------------------------------------- helpers

    private static String ruleSummary(Mode mode, Pricing p, List<String> sizes, Map<String, BigDecimal> sizePrices) {
        return switch (mode) {
            case SAME -> "Prices: " + money(p.price()) + " for all";
            case STEP_UP, STEP_DOWN -> {
                List<String> steps = new ArrayList<>();
                for (int i = 0; i < sizes.size(); i++) {
                    BigDecimal v = rulePrice(mode, p, i, sizes.get(i), sizePrices);
                    steps.add(v == null ? "?" : money(v));
                }
                yield "Prices: " + (mode == Mode.STEP_UP ? "going up" : "going down") + " with size, "
                        + String.join(" → ", steps);
            }
            case PER_SIZE -> {
                List<String> each = new ArrayList<>();
                for (String s : sizes) each.add(s + " " + (sizePrices.get(s) == null ? "?" : money(sizePrices.get(s))));
                yield "Prices per size: " + String.join(", ", each);
            }
            case PER_CELL -> "Prices: each variant has its own";
        };
    }

    static String cellKey(String color, String size) {
        return (color == null ? "" : color.toLowerCase(Locale.ROOT)) + "|" + (size == null ? "" : size.toLowerCase(Locale.ROOT));
    }

    private static String label(String color, String size) {
        return new VariantCombos.Combo(color, size).label();
    }

    private static Map<String, String> byKey(List<String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String v : values) out.put(v.toLowerCase(Locale.ROOT), v);
        return out;
    }

    /** The list's spelling of a value staff referred to, or null (with an error) when it is not on the list. */
    private static String known(String raw, Map<String, String> byKey, String what, List<String> errors) {
        String c = ProductOptionsService.clean(raw);
        if (c == null) {
            errors.add("A " + what + " is missing from one of the exceptions.");
            return null;
        }
        String found = byKey.get(c.toLowerCase(Locale.ROOT));
        if (found == null) {
            errors.add("\"" + c + "\" is not one of the " + what + "s.");
        }
        return found;
    }

    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    static String money(BigDecimal amount) {
        if (amount == null) {
            return "KSh ?"; // a rule still missing its numbers (already an error); the summary must not break on it
        }
        NumberFormat f = NumberFormat.getNumberInstance(Locale.US);
        f.setMaximumFractionDigits(2);
        f.setMinimumFractionDigits(amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return "KSh " + f.format(amount);
    }

    /** CIE L*a*b* of a "#RRGGBB" dot (D65), for telling two dots apart. */
    private static double[] lab(String hex) {
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            rgb[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        double x = (rgb[0] * 0.4124 + rgb[1] * 0.3576 + rgb[2] * 0.1805) / 0.95047;
        double y = (rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722);
        double z = (rgb[0] * 0.0193 + rgb[1] * 0.1192 + rgb[2] * 0.9505) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
    }

    private static double deltaE(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }
}
