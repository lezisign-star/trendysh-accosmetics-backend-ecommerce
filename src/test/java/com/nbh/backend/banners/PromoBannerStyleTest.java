package com.nbh.backend.banners;

import com.nbh.backend.common.exception.UnprocessableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The advanced banner look (owner, 2026-10-31): only what the shop can draw is kept, and it saves and reads back. */
class PromoBannerStyleTest {

    private static PromoBannerService.Style style(String headlineColor, String size, String align, String shade) {
        return new PromoBannerService.Style(headlineColor, null, size, align, shade, null, null, null, null);
    }

    @Test
    void onlyKnownChoicesAndRealColoursAreKept() {
        PromoBannerService.Style s = PromoBannerService.Style.clean(style(" #c2185b ", "l", "CENTER", "strong"));
        assertThat(s.headlineColor()).isEqualTo("#C2185B");
        assertThat(s.headlineSize()).isEqualTo("L");
        assertThat(s.align()).isEqualTo("center");
        assertThat(s.shade()).isEqualTo("strong");
        assertThatThrownBy(() -> PromoBannerService.Style.clean(style("red", null, null, null)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("#C2185B");
        assertThatThrownBy(() -> PromoBannerService.Style.clean(style(null, "XL", null, null)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("S, M, L");
    }

    @Test
    void anEmptyLookIsTheAutomaticOne() {
        assertThat(PromoBannerService.Style.clean(style(null, " ", "", null))).isNull();
        assertThat(PromoBannerService.Style.clean(null)).isNull();
    }

    @Test
    void aLookSavesAndReadsBackAndABrokenOneFallsBackToAutomatic() {
        PromoBannerService.Style s = PromoBannerService.Style.clean(
                new PromoBannerService.Style("#111111", "#FFFFFF", "M", "left", "light", "#FFF1F5", "#C2185B",
                        "#FFFFFF", "display"));
        String json = PromoBannerService.writeStyle(s);
        assertThat(json).doesNotContain("null");
        assertThat(PromoBannerService.readStyle(json)).isEqualTo(s);
        assertThat(PromoBannerService.readStyle("{not json")).isNull();
        assertThat(PromoBannerService.writeStyle(null)).isNull();
    }
}
