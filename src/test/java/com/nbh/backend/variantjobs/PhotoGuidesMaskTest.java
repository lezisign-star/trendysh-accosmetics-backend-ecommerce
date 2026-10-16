package com.nbh.backend.variantjobs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A group can have its own marked part: a black-bottle series changes the bottle, the rest only the cap. */
class PhotoGuidesMaskTest {

    private final PhotoGuides guides = new PhotoGuides(
            new VariantJobRequest.PhotoGuide("Only the cap top", null, "https://x.test/cap.png", "https://x.test/main.jpg"),
            List.of(new VariantJobRequest.PhotoGroup(List.of("Nude Pink CA1538"), "Black bottle", null,
                    "https://x.test/bottle.png", "https://x.test/main.jpg")),
            List.of());

    @Test
    void aGroupsOwnMarkedPartIsUsedForItsColoursAndTheGeneralOneForTheRest() {
        assertThat(guides.maskFor("Nude Pink CA1538").url()).isEqualTo("https://x.test/bottle.png");
        assertThat(guides.maskFor("nude pink ca1538").url()).isEqualTo("https://x.test/bottle.png");
        assertThat(guides.maskFor("Hot Pink PU30").url()).isEqualTo("https://x.test/cap.png");
    }

    @Test
    void aGroupWithoutItsOwnMarkedPartUsesTheGeneralOne() {
        PhotoGuides g = new PhotoGuides(
                new VariantJobRequest.PhotoGuide(null, null, "https://x.test/cap.png", null),
                List.of(new VariantJobRequest.PhotoGroup(List.of("Rose RE1"), "Black", null, null, null)), List.of());
        assertThat(g.maskFor("Rose RE1").url()).isEqualTo("https://x.test/cap.png");
    }

    @Test
    void aMarkIsUsedOnlyOnThePhotoItWasMarkedOn() {
        PhotoGuides.Mask m = guides.maskFor("Hot Pink PU30");
        assertThat(m.fits("https://x.test/main.jpg")).isTrue();
        assertThat(m.fits("https://x.test/50ml-bottle.jpg")).isFalse();
        // A mark from before the photo was recorded is used as it is (the shape check still guards it).
        assertThat(new PhotoGuides.Mask("u", null).fits("anything")).isTrue();
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
