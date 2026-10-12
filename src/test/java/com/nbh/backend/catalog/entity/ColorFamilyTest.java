package com.nbh.backend.catalog.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColorFamilyTest {

    /** The shades from the approved mockup and the shop's own generated variants, with the family a shopper would file them under. */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "Ruby Red,       #9B111E, RED",
            "Cherry,         #C21E3A, RED",
            "Wine,           #6B1630, RED",
            "Nude Beige,     #C8A28A, NUDE",
            "Champagne,      #D9C29A, NUDE",
            "Coral Kiss,     #F2705E, CORAL",
            "Berry Plum,     #6E2A4D, PURPLE",
            "Lilac,          #B49ACB, PURPLE",
            "Blush,          #E9A3B2, PINK",
            "Hot Pink,       #E0287A, PINK",
            "Mocha,          #7B4B3A, BROWN",
            "Espresso,       #4A2E25, BROWN",
            "Gold Shimmer,   #C9A24A, GOLD",
            "Jet Black,      #1B1718, BLACK",
            "Snow,           #FFFFFF, WHITE",
            "Slate,          #808080, GREY",
            "Emerald Green,  #50C878, GREEN",
            "Navy Blue,      #1F2A5A, BLUE",
            "Lemon,          #F4E04D, YELLOW",
            "Tangerine,      #F28500, ORANGE"
    })
    void aDotIsFiledUnderTheFamilyItLooksLike(String name, String hex, ColorFamily expected) {
        assertThat(ColorFamily.fromHex(hex)).as(name).isEqualTo(expected);
    }

    @Test
    void theHashAnAlphaPairAndCaseDoNotMatter() {
        assertThat(ColorFamily.fromHex("9b111e")).isEqualTo(ColorFamily.RED);
        assertThat(ColorFamily.fromHex("#9B111EFF")).isEqualTo(ColorFamily.RED);
    }

    @Test
    void noDotOrABadOneHasNoFamily() {
        assertThat(ColorFamily.fromHex(null)).isNull();
        assertThat(ColorFamily.fromHex("")).isNull();
        assertThat(ColorFamily.fromHex("red")).isNull();
        assertThat(ColorFamily.fromHex("#12345")).isNull();
    }

    @Test
    void staffCanTypeAFamilyInAnyCaseButNotAMadeUpOne() {
        assertThat(ColorFamily.parse("gold")).isEqualTo(ColorFamily.GOLD);
        assertThat(ColorFamily.parse(" Clear ")).isEqualTo(ColorFamily.CLEAR);
        assertThat(ColorFamily.parse("")).isNull();
        assertThat(ColorFamily.parse(null)).isNull();
        assertThatThrownBy(() -> ColorFamily.parse("Sparkly")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sparkly");
    }

    @Test
    void aStoredValueThisVersionDoesNotKnowIsIgnoredRatherThanBreakingTheShop() {
        assertThat(ColorFamily.ofStored("TEAL")).isNull();
        assertThat(ColorFamily.ofStored("PINK")).isEqualTo(ColorFamily.PINK);
    }

    @Test
    void labelsReadAsWords() {
        assertThat(ColorFamily.RED.label()).isEqualTo("Red");
        assertThat(ColorFamily.GREY.label()).isEqualTo("Grey");
    }
}
