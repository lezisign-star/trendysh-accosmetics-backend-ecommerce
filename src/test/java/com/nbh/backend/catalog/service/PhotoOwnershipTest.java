package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's rule (2026-10-07): "we must never have shared photos among the related products". A photo belongs to
 * exactly one option; a shopper opening an option sees that option's photos only.
 */
class PhotoOwnershipTest {

    private final Product product = new Product();
    private final ProductVariant red = option("Red");
    private final ProductVariant blue = option("Blue");
    private final List<ProductImage> photos = new ArrayList<>();
    private int order = 0;

    PhotoOwnershipTest() {
        product.setId(UUID.randomUUID());
    }

    private ProductVariant option(String name) {
        ProductVariant v = new ProductVariant();
        v.setId(UUID.randomUUID());
        v.setVariantName(name);
        v.setProduct(product);
        return v;
    }

    private ProductImage photo(String url, ProductVariant owner, boolean main) {
        ProductImage i = new ProductImage();
        i.setId(UUID.randomUUID());
        i.setProduct(product);
        i.setVariant(owner);
        i.setUrl(url);
        i.setPrimary(main);
        i.setSortOrder(order++);
        photos.add(i);
        return i;
    }

    private List<String> urls(List<ProductImage> list) {
        return list.stream().map(ProductImage::getUrl).toList();
    }

    @Test
    void aShopperOnAnOptionSeesThatOptionsPhotosAndNeverASiblings() {
        photo("red-1", red, true);
        photo("red-2", red, false);
        photo("blue-1", blue, true);

        assertThat(urls(PhotoOwnership.galleryFor(red.getId(), false, photos))).containsExactly("red-1", "red-2");
        assertThat(urls(PhotoOwnership.galleryFor(blue.getId(), false, photos))).containsExactly("blue-1");
    }

    @Test
    void aSharedProductLevelPhotoOnAProductWithSeveralOptionsIsShownNowhere() {
        photo("shared", null, true);
        photo("red-1", red, true);

        assertThat(urls(PhotoOwnership.galleryFor(red.getId(), false, photos))).containsExactly("red-1");
        assertThat(PhotoOwnership.galleryFor(blue.getId(), false, photos)).isEmpty();
        assertThat(PhotoOwnership.coverFor(blue.getId(), false, photos)).isEmpty();
    }

    @Test
    void onAProductWithASingleOptionAPhotoStillFiledAtProductLevelIsThatOptionsOwn() {
        photo("legacy", null, true);
        photo("own", red, false);

        assertThat(urls(PhotoOwnership.galleryFor(red.getId(), true, photos))).containsExactly("legacy", "own");
    }

    @Test
    void theMainPhotoLeadsAndTheRestKeepTheirGalleryOrder() {
        photo("a", red, false);
        photo("b", red, false);
        photo("c", red, true);
        photo("d", red, false);

        assertThat(urls(PhotoOwnership.galleryFor(red.getId(), false, photos))).containsExactly("c", "a", "b", "d");
    }

    @Test
    void anOptionWithNoPhotosOfItsOwnHasNoCoverAndNeverBorrowsASiblings() {
        photo("blue-1", blue, true);

        assertThat(PhotoOwnership.coverFor(red.getId(), false, photos)).isEmpty();
        Map<UUID, String> covers = PhotoOwnership.coverUrlByVariant(List.of(red, blue), photos,
                Map.of(product.getId(), 2L));
        assertThat(covers).containsOnlyKeys(blue.getId()).containsEntry(blue.getId(), "blue-1");
    }

    @Test
    void aProductsCoverIsTheFirstOptionThatHasAPhotoElseAnUnassignedOne() {
        photo("unassigned", null, true);
        assertThat(PhotoOwnership.productCover(List.of(red.getId(), blue.getId()), false, photos))
                .hasValueSatisfying(p -> assertThat(p.getUrl()).isEqualTo("unassigned"));

        photo("blue-1", blue, true);
        assertThat(PhotoOwnership.productCover(List.of(red.getId(), blue.getId()), false, photos))
                .hasValueSatisfying(p -> assertThat(p.getUrl()).isEqualTo("blue-1"));

        photo("red-1", red, true);
        assertThat(PhotoOwnership.productCover(List.of(red.getId(), blue.getId()), false, photos))
                .hasValueSatisfying(p -> assertThat(p.getUrl()).isEqualTo("red-1"));
    }

    @Test
    void coverUrlsAreWorkedOutPerOptionAcrossProducts() {
        Product other = new Product();
        other.setId(UUID.randomUUID());
        ProductVariant solo = new ProductVariant();
        solo.setId(UUID.randomUUID());
        solo.setProduct(other);
        ProductImage soloPhoto = new ProductImage();
        soloPhoto.setId(UUID.randomUUID());
        soloPhoto.setProduct(other);
        soloPhoto.setUrl("solo-legacy");
        soloPhoto.setPrimary(true);
        photo("red-1", red, true);

        List<ProductImage> all = new ArrayList<>(photos);
        all.add(soloPhoto);
        Map<UUID, Long> counts = PhotoOwnership.optionCounts(List.of(red, blue, solo));

        assertThat(counts).containsEntry(product.getId(), 2L).containsEntry(other.getId(), 1L);
        Map<UUID, String> covers = PhotoOwnership.coverUrlByVariant(List.of(red, blue, solo), all, counts);
        assertThat(covers).containsEntry(red.getId(), "red-1").containsEntry(solo.getId(), "solo-legacy")
                .doesNotContainKey(blue.getId());
    }

    @Test
    void photosWithTheSameOwnerAreGroupedTogetherIncludingTheUnassignedGroup() {
        photo("r", red, true);
        photo("u1", null, true);
        photo("u2", null, false);

        assertThat(urls(PhotoOwnership.sameOwner(photos, null))).containsExactly("u1", "u2");
        assertThat(urls(PhotoOwnership.sameOwner(photos, red.getId()))).containsExactly("r");
    }
}
