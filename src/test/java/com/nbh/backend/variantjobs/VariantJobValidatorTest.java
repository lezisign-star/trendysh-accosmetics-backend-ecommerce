package com.nbh.backend.variantjobs;

import com.nbh.backend.variantjobs.VariantJobRequest.*;
import com.nbh.backend.variantjobs.VariantJobValidator.Context;
import com.nbh.backend.variantjobs.VariantJobValidator.Plan;
import com.nbh.backend.variantjobs.VariantJobValidator.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VariantJobValidatorTest {

    private static final Context CTX = new Context("Velvet Matte Lipstick", Set.of(), true, Set.of(), true);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static List<ColorIn> colors(String... names) {
        return java.util.Arrays.stream(names).map(n -> new ColorIn(n, null, null)).toList();
    }

    private static List<SizeIn> sizes(String... names) {
        return java.util.Arrays.stream(names).map(n -> new SizeIn(n, null, null)).toList();
    }

    private static VariantJobRequest req(List<ColorIn> colors, List<SizeIn> sizes, Boolean every, List<CellIn> cells,
                                         Pricing pricing, List<SizeAvailability> availability) {
        return new VariantJobRequest(UUID.randomUUID(), null, colors, sizes, every, cells, pricing, availability, null,
                false, null);
    }

    private static Pricing same(String price) {
        return new Pricing(Mode.SAME, d(price), null, null, null, null, null);
    }

    private static Row row(Plan plan, String label) {
        return plan.rows().stream().filter(r -> r.label().equals(label)).findFirst().orElseThrow(() -> new AssertionError(label));
    }

    /** The owner's example: a rule, one colour that costs more in every size, one single price, one size in two colours. */
    @Test
    void aRulePlusAFewExceptionsPricesEveryVariantAndSaysWhatIsDifferent() {
        Pricing pricing = new Pricing(Mode.STEP_UP, null, d("400"), d("250"), null,
                List.of(new ColorAdjustment("gold shimmer", d("150"))),
                List.of(new CellOverride("Ruby Red", "Travel 1g", d("350"))));
        Plan plan = VariantJobValidator.plan(req(colors("Ruby Red", "Nude Beige", "Gold Shimmer"),
                sizes("Travel 1g", "Mini 2g", "Full 3.5g"), false, null, pricing,
                List.of(new SizeAvailability("Travel 1g", List.of("Ruby Red", "Nude Beige")))), CTX);

        assertThat(plan.errors()).isEmpty();
        assertThat(plan.toMake()).hasSize(8);
        assertThat(row(plan, "Ruby Red / Travel 1g").price()).isEqualByComparingTo("350");
        assertThat(row(plan, "Ruby Red / Travel 1g").priceFrom()).isEqualTo("own price");
        assertThat(row(plan, "Nude Beige / Travel 1g").price()).isEqualByComparingTo("400");
        assertThat(row(plan, "Gold Shimmer / Travel 1g").included()).isFalse();
        assertThat(row(plan, "Nude Beige / Mini 2g").price()).isEqualByComparingTo("650");
        assertThat(row(plan, "Gold Shimmer / Mini 2g").price()).isEqualByComparingTo("800");
        assertThat(row(plan, "Gold Shimmer / Full 3.5g").price()).isEqualByComparingTo("1050");
        assertThat(row(plan, "Gold Shimmer / Full 3.5g").name()).isEqualTo("Velvet Matte Lipstick – Gold Shimmer / Full 3.5g");
        assertThat(plan.summary()).containsExactly(
                "Prices: going up with size, KSh 400 → KSh 650 → KSh 900",
                "Gold Shimmer: +KSh 150 in every size",
                "Ruby Red / Travel 1g: KSh 350",
                "Travel 1g: only Ruby Red, Nude Beige");
        assertThat(plan.estimate().photoCalls()).as("one recolour per colour, no label text").isEqualTo(3);
        assertThat(plan.estimate().descriptionCalls()).isEqualTo(1);
    }

    @Test
    void changingTheRuleKeepsTheExceptions() {
        Pricing pricing = new Pricing(Mode.STEP_UP, null, d("500"), d("300"), null,
                List.of(new ColorAdjustment("Gold Shimmer", d("150"))), null);
        Plan plan = VariantJobValidator.plan(req(colors("Ruby Red", "Gold Shimmer"), sizes("Mini 2g", "Full 3.5g"),
                true, null, pricing, null), CTX);
        assertThat(row(plan, "Gold Shimmer / Full 3.5g").price()).isEqualByComparingTo("950");
        assertThat(row(plan, "Ruby Red / Full 3.5g").price()).isEqualByComparingTo("800");
    }

    @Test
    void perSizeAndSamePricesFillEveryCell() {
        Pricing perSize = new Pricing(Mode.PER_SIZE, null, null, null, Map.of("50ML", d("900"), "100ml", d("1500")), null, null);
        Plan plan = VariantJobValidator.plan(req(List.of(), sizes("50ml", "100ml"), true, null, perSize, null), CTX);
        assertThat(plan.errors()).isEmpty();
        assertThat(row(plan, "50ml").price()).isEqualByComparingTo("900");
        assertThat(row(plan, "100ml").price()).isEqualByComparingTo("1500");
        assertThat(plan.summary()).containsExactly("Prices per size: 50ml KSh 900, 100ml KSh 1,500");

        Plan flat = VariantJobValidator.plan(req(colors("Red", "Blue"), List.of(), true, null, same("450"), null), CTX);
        assertThat(flat.toMake()).extracting(Row::price).allMatch(p -> p.compareTo(d("450")) == 0);
    }

    @Test
    void perCellPricesComeFromTheCellsAndAMissingOneIsAnError() {
        Pricing perCell = new Pricing(Mode.PER_CELL, null, null, null, null, null, null);
        Plan plan = VariantJobValidator.plan(req(colors("Red", "Blue"), sizes("S", "M"), true,
                List.of(new CellIn("Red", "S", null, d("500"), null, null), new CellIn("Red", "M", null, d("600"), null, null),
                        new CellIn("Blue", "S", null, d("510"), null, null)), perCell, null), CTX);
        assertThat(row(plan, "Red / M").price()).isEqualByComparingTo("600");
        assertThat(plan.errors()).containsExactly("Blue / M has no price.");
    }

    @Test
    void aScreenThatWorkedAPriceOutDifferentlyIsRefused() {
        Plan plan = VariantJobValidator.plan(req(colors("Red"), sizes("S"), true,
                List.of(new CellIn("Red", "S", null, d("499"), null, null)), same("500"), null), CTX);
        assertThat(plan.errors()).singleElement().asString().contains("the screen says KSh 499 but the prices work out to KSh 500");
    }

    @Test
    void everyCombinationTickedWithSomethingLeftOutIsRefused() {
        Plan plan = VariantJobValidator.plan(req(colors("Red", "Blue"), sizes("S", "M"), true,
                List.of(new CellIn("Blue", "M", false, null, null, null)), same("500"), null), CTX);
        assertThat(plan.errors()).anyMatch(e -> e.contains("\"Every size comes in every colour\" is ticked"));

        Plan unticked = VariantJobValidator.plan(req(colors("Red", "Blue"), sizes("S", "M"), false,
                List.of(new CellIn("Blue", "M", false, null, null, null)), same("500"), null), CTX);
        assertThat(unticked.errors()).isEmpty();
        assertThat(unticked.toMake()).hasSize(3);
        assertThat(unticked.summary()).contains("Not made: Blue / M");
    }

    @Test
    void namesThatAreMissingRepeatedOrUnknownAreErrors() {
        Pricing pricing = new Pricing(Mode.SAME, d("500"), null, null, null,
                List.of(new ColorAdjustment("Purple", d("50"))), null);
        Plan plan = VariantJobValidator.plan(req(colors("Red", " red ", " "), sizes("S"), true, null, pricing, null), CTX);
        assertThat(plan.errors()).contains("The colour \"red\" is listed twice.", "A colour has no name.",
                "\"Purple\" is not one of the colours.");
    }

    @Test
    void ruleMistakesAreNamed() {
        Plan noMode = VariantJobValidator.plan(req(colors("Red"), List.of(), true, null, null, null), CTX);
        assertThat(noMode.errors()).anyMatch(e -> e.startsWith("Choose how prices work"));

        Plan noPrice = VariantJobValidator.plan(req(colors("Red"), List.of(), true, null,
                new Pricing(Mode.SAME, null, null, null, null, null, null), null), CTX);
        assertThat(noPrice.errors()).contains("Give the price for all of them.");
        assertThat(noPrice.summary()).containsExactly("Prices: KSh ? for all");

        Plan oneSizeStep = VariantJobValidator.plan(req(List.of(), sizes("S"), true, null,
                new Pricing(Mode.STEP_UP, null, d("100"), d("50"), null, null, null), null), CTX);
        assertThat(oneSizeStep.errors()).contains("Prices that go up or down with size need at least two sizes.");

        Plan missingSize = VariantJobValidator.plan(req(List.of(), sizes("S", "M"), true, null,
                new Pricing(Mode.PER_SIZE, null, null, null, Map.of("S", d("100")), null, null), null), CTX);
        assertThat(missingSize.errors()).contains("Give the price for M.");

        Plan negative = VariantJobValidator.plan(req(List.of(), sizes("S", "M", "L"), true, null,
                new Pricing(Mode.STEP_DOWN, null, d("200"), d("150"), null, null, null), null), CTX);
        assertThat(negative.errors()).contains("L would cost KSh -100. A price must be more than 0.");

        Plan nothing = VariantJobValidator.plan(req(List.of(), List.of(), true, null, same("1"), null), CTX);
        assertThat(nothing.errors()).contains("Give at least one colour or one size.");
    }

    @Test
    void combinationsTheProductHasAreSkippedAndNothingNewIsAnError() {
        Context has = new Context("P", Set.of(VariantJobValidator.cellKey("Red", null)), true, Set.of(), true);
        Plan plan = VariantJobValidator.plan(req(colors("Red", "Blue"), List.of(), true, null, same("100"), null), has);
        assertThat(plan.toMake()).extracting(Row::label).containsExactly("Blue");
        assertThat(plan.warnings()).contains("Already on this product, will be skipped: Red.");

        Plan none = VariantJobValidator.plan(req(colors("Red"), List.of(), true, null, same("100"), null), has);
        assertThat(none.errors()).anyMatch(e -> e.startsWith("There is nothing new to make"));
    }

    @Test
    void moreThanTheLimitInOneGoIsRefused() {
        List<ColorIn> many = new ArrayList<>();
        for (int i = 0; i < 16; i++) many.add(new ColorIn("Shade " + i, null, null));
        Plan plan = VariantJobValidator.plan(req(many, sizes("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), true,
                null, same("100"), null), CTX);
        assertThat(plan.errors()).anyMatch(e -> e.startsWith("That makes 160 variants; the most in one go is 150"));
    }

    @Test
    void withoutAnyPhotoToWorkFromNothingCanStartButASizePhotoIsEnough() {
        Context noPhoto = new Context("P", Set.of(), false, Set.of(), true);
        assertThat(VariantJobValidator.plan(req(colors("Red"), List.of(), true, null, same("100"), null), noPhoto).errors())
                .anyMatch(e -> e.startsWith("The product has no photo"));

        UUID photo = UUID.randomUUID();
        Context sizePhotoOnly = new Context("P", Set.of(), false, Set.of(photo), true);
        Plan plan = VariantJobValidator.plan(req(List.of(), List.of(new SizeIn("L", null, photo)), true, null, same("100"), null),
                sizePhotoOnly);
        assertThat(plan.errors()).isEmpty();
        assertThat(VariantJobValidator.plan(req(List.of(), List.of(new SizeIn("L", null, UUID.randomUUID())), true, null,
                same("100"), null), sizePhotoOnly).errors()).anyMatch(e -> e.contains("not one of this product's photos"));
    }

    @Test
    void unusualPricesAreWarnedAboutButAllowed() {
        Pricing down = new Pricing(Mode.STEP_DOWN, null, d("900"), d("100"), null, null,
                List.of(new CellOverride("Red", "M", d("12000"))));
        Plan plan = VariantJobValidator.plan(req(colors("Red", "Blue", "Green"), sizes("S", "M", "L"), true, null, down, null), CTX);
        assertThat(plan.errors()).isEmpty();
        assertThat(plan.warnings()).anyMatch(w -> w.startsWith("Blue / M (KSh 800) is cheaper than Blue / S (KSh 900)"));
        assertThat(plan.warnings()).anyMatch(w -> w.startsWith("Red / M costs KSh 12,000, far from the others"));
    }

    @Test
    void nearlyIdenticalDotsAndBadDotsAndFamilies() {
        Plan plan = VariantJobValidator.plan(req(List.of(new ColorIn("Ruby", "#9B111E", null),
                new ColorIn("Ruby Deep", "#9C121F", "red"), new ColorIn("Odd", "#XYZ", "Sparkly")), List.of(), true, null,
                same("100"), null), CTX);
        assertThat(plan.warnings()).anyMatch(w -> w.startsWith("Ruby and Ruby Deep have nearly the same dot"));
        assertThat(plan.errors()).anyMatch(e -> e.contains("\"#XYZ\" is not a colour"));
        assertThat(plan.errors()).anyMatch(e -> e.contains("\"Sparkly\" is not a colour family"));
    }

    @Test
    void openingStockIsCheckedAndStockWithoutACostIsWarnedAbout() {
        Plan plan = VariantJobValidator.plan(req(colors("Red", "Blue", "Green"), List.of(), true,
                List.of(new CellIn("Red", null, null, null, d("2.5"), null), new CellIn("Blue", null, null, null, d("-1"), null),
                        new CellIn("Green", null, null, null, d("12"), null)), same("100"), null), CTX);
        assertThat(plan.errors()).contains("Red: opening stock must be a whole number.", "Blue: opening stock can't be negative.");
        assertThat(plan.warnings()).anyMatch(w -> w.startsWith("Green: opening stock without a cost"));
    }

    @Test
    void aStockValueOrCostTheStockTablesCannotHoldIsRefusedBeforeGenerate() {
        Plan plan = VariantJobValidator.plan(req(colors("Red", "Blue", "Green"), List.of(), true,
                List.of(new CellIn("Red", null, null, null, d("1000000"), d("10000")),
                        new CellIn("Blue", null, null, null, d("5"), d("20000000")),
                        new CellIn("Green", null, null, null, d("100"), d("500"))), same("100"), null), CTX);
        assertThat(plan.errors()).anyMatch(e -> e.startsWith("Red: that stock at that cost is worth more than"));
        assertThat(plan.errors()).anyMatch(e -> e.startsWith("Blue: that cost is too much"));
        assertThat(plan.errors()).noneMatch(e -> e.startsWith("Green:"));
    }

    @Test
    void photoCallsCountOneRecolourPerColourPlusOneLabelEditPerVariant() {
        UUID bigPhoto = UUID.randomUUID();
        Context ctx = new Context("P", Set.of(), true, Set.of(bigPhoto), true);
        Plan labelled = VariantJobValidator.plan(req(colors("Red", "Blue"),
                List.of(new SizeIn("50ml", "50ml", null), new SizeIn("100ml", "100 ML", null)), true, null, same("1"), null), ctx);
        assertThat(labelled.estimate().photoCalls()).isEqualTo(2 + 4);

        Plan sizePhoto = VariantJobValidator.plan(req(colors("Red", "Blue"),
                List.of(new SizeIn("50ml", null, null), new SizeIn("100ml", null, bigPhoto)), true, null, same("1"), null), ctx);
        assertThat(sizePhoto.estimate().photoCalls()).as("each colour recoloured from both starting photos").isEqualTo(4);

        Plan sizesOnly = VariantJobValidator.plan(req(List.of(), sizes("S", "M"), true, null, same("1"), null), ctx);
        assertThat(sizesOnly.estimate().photoCalls()).as("the product's photo as it is").isZero();
    }
}
