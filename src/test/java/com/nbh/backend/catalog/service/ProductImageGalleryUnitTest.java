package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.dto.CatalogImageDto;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * A product can have several images, exactly one of which is primary. The
 * storefront list shows only the primary, so the gallery must never end up
 * with images but no primary (first image, or the primary being removed).
 * Uses an in-memory list as the "table" so assertions are on the resulting
 * gallery, not on mock interactions.
 */
@ExtendWith(MockitoExtension.class)
class ProductImageGalleryUnitTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private BrandRepository brandRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ProductImageRepository imageRepository;
    @Mock private com.nbh.backend.stock.repository.StockBalanceRepository balanceRepository;

    private ProductService service;
    private Product product;
    private final List<ProductImage> table = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, variantRepository, brandRepository, categoryRepository,
                imageRepository, balanceRepository);

        product = new Product();
        product.setId(UUID.randomUUID());
        lenient().when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        lenient().when(productRepository.existsById(product.getId())).thenReturn(true);

        lenient().when(imageRepository.findByProductIdOrderBySortOrderAsc(product.getId()))
                .thenAnswer(inv -> table.stream()
                        .sorted(java.util.Comparator.comparingInt(ProductImage::getSortOrder)).toList());
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

    private CatalogImageDto attach(String url, boolean primary) {
        return service.attachImage(product.getId(),
                new AttachImageRequest(url, null, null, null, primary, null, null, null));
    }

    private List<String> primaries() {
        return table.stream().filter(ProductImage::isPrimary).map(ProductImage::getUrl).toList();
    }

    @Test
    void theFirstImageBecomesPrimaryEvenWhenTheRequestSaysOtherwise() {
        attach("a.jpg", false);

        assertThat(primaries()).containsExactly("a.jpg");
    }

    @Test
    void severalImagesAreKeptInOrderWithExactlyOnePrimary() {
        attach("a.jpg", false);
        attach("b.jpg", false);
        attach("c.jpg", false);

        assertThat(service.listImages(product.getId())).extracting(CatalogImageDto::url)
                .containsExactly("a.jpg", "b.jpg", "c.jpg");
        assertThat(primaries()).containsExactly("a.jpg");
    }

    @Test
    void attachingAnotherPrimaryDemotesTheOldOne() {
        attach("a.jpg", false);
        attach("b.jpg", true);

        assertThat(primaries()).containsExactly("b.jpg");
    }

    @Test
    void listImagesPutsThePrimaryFirst() {
        attach("a.jpg", false);
        attach("b.jpg", false);
        CatalogImageDto c = attach("c.jpg", false);
        service.setPrimaryImage(product.getId(), c.id());

        assertThat(service.listImages(product.getId())).extracting(CatalogImageDto::url)
                .containsExactly("c.jpg", "a.jpg", "b.jpg");
    }

    @Test
    void removingThePrimaryPromotesTheNextImageSoTheGalleryNeverHasNoPrimary() {
        CatalogImageDto a = attach("a.jpg", false);
        attach("b.jpg", false);

        service.removeImage(product.getId(), a.id());

        assertThat(table).extracting(ProductImage::getUrl).containsExactly("b.jpg");
        assertThat(primaries()).containsExactly("b.jpg");
    }

    @Test
    void removingANonPrimaryImageLeavesThePrimaryAlone() {
        attach("a.jpg", false);
        CatalogImageDto b = attach("b.jpg", false);

        service.removeImage(product.getId(), b.id());

        assertThat(primaries()).containsExactly("a.jpg");
    }

    @Test
    void anImageOfAnotherProductCannotBeRemovedOrPromoted() {
        attach("a.jpg", false);
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.removeImage(product.getId(), stranger))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.setPrimaryImage(product.getId(), stranger))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(table).hasSize(1);
    }
}
