package com.nbh.backend.variantjobs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A group can have its own marked part: a black-bottle series changes the bottle, the rest only the cap. */
class PhotoGuidesMaskTest {

    private final PhotoGuides guides = new PhotoGuides(
            new VariantJobRequest.PhotoGuide("Only the cap top", null, "https://x.test/cap.png"),
            List.of(new VariantJobRequest.PhotoGroup(List.of("Nude Pink CA1538"), "Black bottle", null,
                    "https://x.test/bottle.png")),
            List.of());

    @Test
    void aGroupsOwnMarkedPartIsUsedForItsColoursAndTheGeneralOneForTheRest() {
        assertThat(guides.maskFor("Nude Pink CA1538")).isEqualTo("https://x.test/bottle.png");
        assertThat(guides.maskFor("nude pink ca1538")).isEqualTo("https://x.test/bottle.png");
        assertThat(guides.maskFor("Hot Pink PU30")).isEqualTo("https://x.test/cap.png");
    }

    @Test
    void aGroupWithoutItsOwnMarkedPartUsesTheGeneralOne() {
        PhotoGuides g = new PhotoGuides(
                new VariantJobRequest.PhotoGuide(null, null, "https://x.test/cap.png"),
                List.of(new VariantJobRequest.PhotoGroup(List.of("Rose RE1"), "Black", null, null)), List.of());
        assertThat(g.maskFor("Rose RE1")).isEqualTo("https://x.test/cap.png");
    }

    @Test
    void noMarkedPartAnywhereMeansNone() {
        assertThat(PhotoGuides.none().maskFor("Anything")).isNull();
    }

    @Test
    void onlyTheShopsOwnPicturesAreAccepted() {
        assertThatThrownBy(() -> guides.check(url -> url.equals("https://x.test/cap.png")))
                .hasMessageContaining("isn't one of the shop's own");
        guides.check(url -> true);
    }
}
