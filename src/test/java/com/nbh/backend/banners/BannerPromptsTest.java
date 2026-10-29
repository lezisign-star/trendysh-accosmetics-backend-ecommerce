package com.nbh.backend.banners;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The container a picture fits, and the line kept honest (owner, 2026-10-29). */
class BannerPromptsTest {

    @Test
    void theContainerFollowsWhereThePictureIsCalm() {
        assertThat(BannerPrompts.container("top", 2, false)).isEqualTo("card-words-top");
        assertThat(BannerPrompts.container("bottom", 4, false)).isEqualTo("card-words-bottom");
        // a little busy, or no calm third: the words go over a darkened lower half
        assertThat(BannerPrompts.container("bottom", 6, false)).isEqualTo("card-words-over");
        assertThat(BannerPrompts.container("none", 7, false)).isEqualTo("card-words-over");
        // too busy, or words drawn into it: dropped
        assertThat(BannerPrompts.container("top", 9, false)).isNull();
        assertThat(BannerPrompts.container("top", 1, true)).isNull();
    }

    @Test
    void theLineNeverCarriesNumbersPricesOrPercentages() {
        assertThat(BannerPrompts.cleanLine("Weekend iko sawa, glow up!", "fallback")).isEqualTo("Weekend iko sawa, glow up!");
        assertThat(BannerPrompts.cleanLine("\"Sasa glow!\" #weekend", "fallback")).isEqualTo("Sasa glow! weekend");
        for (String bad : List.of("Up to 25% off", "Only KSh 300", "2 for 1 today", "Lowest kes ever", "")) {
            assertThat(BannerPrompts.cleanLine(bad, "fallback")).as(bad).isEqualTo("fallback");
        }
        assertThat(BannerPrompts.cleanLine("x".repeat(61), "fallback")).isEqualTo("fallback");
        assertThat(BannerPrompts.cleanLine(null, "fallback")).isEqualTo("fallback");
    }

    @Test
    void thePictureIsAskedForWithoutWordsAndWithTheRealProduct() {
        Occasion o = new Occasion();
        o.setName("Black November");
        o.setTone("black and gold");
        String card = BannerPrompts.picture(o, "a family at home", "Gel polish", false, BannerPrompts.Calm.TOP);
        assertThat(card).contains("Portrait 2:3", "top third", "exactly as in the photo", "no text");
        assertThat(BannerPrompts.picture(o, "a set", "Nails supplies", true, BannerPrompts.Calm.BOTTOM))
                .contains("Square 1:1").doesNotContain("third");
    }
}
