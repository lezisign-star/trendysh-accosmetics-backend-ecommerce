package com.nbh.backend.variantgen;

import com.nbh.backend.variantgen.VariantCombos.Combo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VariantGenerationUnitTest {

    @Test
    void coloursAndSizesMakeEveryCombination() {
        List<Combo> combos = VariantCombos.build(List.of("Red", "Blue"), List.of("S", "L"));
        assertThat(combos).extracting(Combo::label).containsExactly("Red / S", "Red / L", "Blue / S", "Blue / L");
    }

    @Test
    void oneAxisAloneIsEnoughAndNeitherMakesNothing() {
        assertThat(VariantCombos.build(List.of("Red"), null)).extracting(Combo::label).containsExactly("Red");
        assertThat(VariantCombos.build(null, List.of("500ml"))).extracting(Combo::label).containsExactly("500ml");
        assertThat(VariantCombos.build(List.of(), List.of(" "))).isEmpty();
    }

    @Test
    void namesAreCleanedAndRepeatsInAnyCaseDropped() {
        assertThat(VariantCombos.clean(Arrays.asList("  Emerald   Green ", "emerald green", null, "", "Blue")))
                .containsExactly("Emerald Green", "Blue");
    }

    @Test
    void variantNameIsFullTitlePlusOptionAndFitsTheColumn() {
        assertThat(VariantCombos.variantName("JIO Pot Set", "Emerald Green")).isEqualTo("JIO Pot Set – Emerald Green");
        String name = VariantCombos.variantName("x".repeat(400), "Emerald Green");
        assertThat(name).hasSizeLessThanOrEqualTo(250).endsWith(" – Emerald Green");
    }

    @Test
    void hexIsAcceptedOnlyWhenItIsOne() {
        assertThat(VariantCombos.normaliseHex("1a7f4b")).isEqualTo("#1A7F4B");
        assertThat(VariantCombos.normaliseHex("#1a7f4b")).isEqualTo("#1A7F4B");
        assertThat(VariantCombos.normaliseHex("green")).isNull();
        assertThat(VariantCombos.normaliseHex(null)).isNull();
    }

    @Test
    void thePhotoPromptAllowsOnlyColourSizeAndLabelChanges() {
        String p = new VariantPhotoPromptBuilder().build("JIO Pot Set", "Emerald Green", "#1A7F4B", "Large",
                "label says 5 pcs", "#F3EBDD");
        assertThat(p).contains("Emerald Green").contains("#1A7F4B").contains("\"Large\"").contains("label says 5 pcs")
                .contains("STRICT RULES").contains("Label text");
        String colourOnly = new VariantPhotoPromptBuilder().build("Pot", "Red", null, null, null, "#F3EBDD");
        assertThat(colourOnly).doesNotContain("- Size:");
    }
}
