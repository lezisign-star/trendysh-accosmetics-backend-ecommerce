package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.*;
import com.nbh.backend.common.exception.UnprocessableException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A photo belongs to exactly one option, and every option has its own main photo (owner, 2026-10-07). An in-memory
 * list stands in for the table so the assertions are on the resulting galleries, not on mock interactions.
 */
@ExtendWith(MockitoExtension.class)
class ProductPhotoOwnershipUnitTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private BrandRepository brandRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ProductImageRepository imageRepository;
    @Mock private com.nbh.backend.stock.service.StockAvailabilityService availabilityService;
    @Mock private ImageAssetCleaner assetCleaner;

    private ProductService service;
    private Product product;
    private ProductVariant red;
    private ProductVariant blue;
    private List<ProductVariant> options;
    private final List<ProductImage> table = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, variantRepository, brandRepository, categoryRepository,
                imageRepository, availabilityService);
        ReflectionTestUtils.setField(service, "assetCleaner", assetCleaner);

        product = new Product();
        product.setId(UUID.randomUUID());
        red = option("Red");
        blue = option("Blue");
        options = new ArrayList<>(List.of(red, blue));

        lenient().when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        lenient().when(productRepository.existsById(product.getId())).thenReturn(true);
        lenient().when(variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId()))
                .thenAnswer(inv -> List.copyOf(options));
        lenient().when(imageRepository.findByProductIdOrderBySortOrderAsc(product.getId()))
                .thenAnswer(inv -> table.stream().sorted(Comparator.comparingInt(ProductImage::getSortOrder)).toList());
        lenient().when(imageRepository.findByVariantIdOrderBySortOrderAsc(any()))
                .thenAnswer(inv -> table.stream()
                        .filter(i -> i.getVariant() != null && i.getVariant().getId().equals(inv.getArgument(0)))
                        .sorted(Comparator.comparingInt(ProductImage::getSortOrder)).toList());
        lenient().when(imageRepository.save(any())).thenAnswer(inv -> {
            ProductImage img = inv.getArgument(0);
            if (img.getId() == null) {
                img.setId(UUID.randomUUID());
            }
            if (!table.contains(img)) {
                table.add(img);
            }
            return img;
        });
        lenient().doAnswer(inv -> table.remove((ProductImage) inv.getArgument(0)))
                .when(imageRepository).delete(any());
    }

    private ProductVariant option(String name) {
        ProductVariant v = new ProductVariant();
        v.setId(UUID.randomUUID());
        v.setProduct(product);
        v.setVariantName(name);
        return v;
    }

    private AdminImageDto attach(String url, ProductVariant owner, boolean main) {
        return service.attachImage(product.getId(), new AttachImageRequest(url,
                null, owner == null ? null : owner.getId(), null, main, null, null, null));
    }

    /** A photo filed at product level, the way every photo was before the one-owner rule. */
    private ProductImage legacyUnassigned(String url, boolean main) {
        ProductImage i = new ProductImage();
        i.setId(UUID.randomUUID());
        i.setProduct(product);
        i.setUrl(url);
        i.setPrimary(main);
        i.setSortOrder(table.size());
        table.add(i);
        return i;
    }

    private List<String> mains() {
        return table.stream().filter(ProductImage::isPrimary).map(ProductImage::getUrl).sorted().toList();
    }

    private List<String> galleryOf(ProductVariant owner) {
        return PhotoOwnership.galleryFor(owner.getId(), options.size() <= 1, table).stream()
                .map(ProductImage::getUrl).toList();
    }

    // ---- who a new photo goes to ----

    @Test
    void withSeveralOptionsTheCallerMustSayWhichOneThePhotoIsFor() {
        assertThatThrownBy(() -> attach("a.jpg", null, false))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("which option");
        assertThat(table).isEmpty();
    }

    @Test
    void aProductWithOneOptionFilesAPhotoUnderItWithoutBeingTold() {
        options.remove(blue);

        AdminImageDto saved = attach("a.jpg", null, false);

        assertThat(saved.variantId()).isEqualTo(red.getId());
        assertThat(galleryOf(red)).containsExactly("a.jpg");
    }

    @Test
    void anOptionOfAnotherProductCannotReceiveAPhoto() {
        ProductVariant stranger = new ProductVariant();
        stranger.setId(UUID.randomUUID());

        assertThatThrownBy(() -> attach("a.jpg", stranger, false)).isInstanceOf(UnprocessableException.class);
        assertThat(table).isEmpty();
    }

    // ---- each option has its own main photo ----

    @Test
    void everyOptionsFirstPhotoIsItsOwnMainPhoto() {
        attach("red-1.jpg", red, false);
        attach("blue-1.jpg", blue, false);
        attach("red-2.jpg", red, false);

        assertThat(mains()).containsExactly("blue-1.jpg", "red-1.jpg");
    }

    @Test
    void makingAnotherPhotoAnOptionsMainOneNeverTouchesTheOtherOptionsMainPhoto() {
        attach("red-1.jpg", red, false);
        AdminImageDto red2 = attach("red-2.jpg", red, false);
        attach("blue-1.jpg", blue, false);

        service.setPrimaryImage(product.getId(), red2.id());

        assertThat(mains()).containsExactly("blue-1.jpg", "red-2.jpg");
        assertThat(galleryOf(red)).containsExactly("red-2.jpg", "red-1.jpg");
        assertThat(galleryOf(blue)).containsExactly("blue-1.jpg");
    }

    @Test
    void attachingANewMainPhotoDemotesOnlyThatOptionsOldOne() {
        attach("red-1.jpg", red, false);
        attach("blue-1.jpg", blue, false);

        attach("red-2.jpg", red, true);

        assertThat(mains()).containsExactly("blue-1.jpg", "red-2.jpg");
    }

    @Test
    void removingAnOptionsMainPhotoPromotesItsOwnNextOneNotASiblings() {
        AdminImageDto red1 = attach("red-1.jpg", red, false);
        attach("blue-1.jpg", blue, false);
        attach("red-2.jpg", red, false);

        service.removeImage(product.getId(), red1.id());

        assertThat(mains()).containsExactly("blue-1.jpg", "red-2.jpg");
    }

    // ---- unassigned photos ----

    @Test
    void anUnassignedPhotoBecomesAnOptionsMainPhotoOnlyIfTheOptionHadNone() {
        ProductImage stray = legacyUnassigned("stray.jpg", true);
        attach("blue-1.jpg", blue, false);

        service.assignImage(product.getId(), stray.getId(), red.getId());
        assertThat(stray.getVariant()).isSameAs(red);
        assertThat(stray.isPrimary()).as("red had no photos, so this is its main one").isTrue();

        ProductImage second = legacyUnassigned("second.jpg", false);
        service.assignImage(product.getId(), second.getId(), blue.getId());
        assertThat(second.isPrimary()).as("blue already has a main photo").isFalse();
        assertThat(galleryOf(blue)).containsExactly("blue-1.jpg", "second.jpg");
    }

    @Test
    void movingAnOptionsMainPhotoAwayLetsItsNextPhotoTakeOver() {
        AdminImageDto red1 = attach("red-1.jpg", red, false);
        attach("red-2.jpg", red, false);

        service.assignImage(product.getId(), red1.id(), blue.getId());

        assertThat(galleryOf(red)).containsExactly("red-2.jpg");
        assertThat(galleryOf(blue)).containsExactly("red-1.jpg");
        assertThat(mains()).containsExactly("red-1.jpg", "red-2.jpg");
    }

    @Test
    void aPhotoCannotBeMovedToAnotherProductsOption() {
        AdminImageDto red1 = attach("red-1.jpg", red, false);

        assertThatThrownBy(() -> service.assignImage(product.getId(), red1.id(), UUID.randomUUID()))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> service.assignImage(product.getId(), UUID.randomUUID(), blue.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void staffSeeEveryLivePhotoWithItsOwnerButNotTheOnesOfARemovedOption() {
        attach("red-1.jpg", red, false);
        attach("blue-1.jpg", blue, false);
        legacyUnassigned("stray.jpg", false);
        options.remove(blue);

        assertThat(service.listImages(product.getId())).extracting(AdminImageDto::url)
                .containsExactlyInAnyOrder("red-1.jpg", "stray.jpg");
    }

    // ---- Cloudinary ----

    @Test
    void removingAPhotoAsksForItsFileToBeReleasedOnCloudinary() {
        AdminImageDto red1 = attach("https://res.cloudinary.com/demo/image/upload/v1/trendysh/products/a.jpg", red, false);

        service.removeImage(product.getId(), red1.id());

        verify(assetCleaner).releaseAfterCommit(List.of("https://res.cloudinary.com/demo/image/upload/v1/trendysh/products/a.jpg"));
    }

    @Test
    void aFailedLookupReleasesNothing() {
        assertThatThrownBy(() -> service.removeImage(product.getId(), UUID.randomUUID()))
                .isInstanceOf(EntityNotFoundException.class);

        verify(assetCleaner, never()).releaseAfterCommit(any());
    }
}
