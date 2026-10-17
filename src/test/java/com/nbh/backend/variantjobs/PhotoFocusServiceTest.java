package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Marking the part that changes sets where the shop's cards crop the product's photos already made (V66). */
class PhotoFocusServiceTest {

    private static final String MASK = "https://res.cloudinary.com/t/mask.png";
    private static final String BASE = "https://res.cloudinary.com/t/base.png";

    private final GeminiImageClient gemini = mock(GeminiImageClient.class);
    private final ProductImageRepository images = mock(ProductImageRepository.class);
    private final PhotoFocusService service = new PhotoFocusService(gemini, images);

    private static GeminiImageClient.Image capMask() throws IOException {
        BufferedImage image = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(78, 38, 44, 84);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new GeminiImageClient.Image(bytes.toByteArray(), "image/png");
    }

    private static ProductImage photo(String url, String colour, ProductImage.ImageSource source) {
        ProductImage i = new ProductImage();
        i.setUrl(url);
        i.setSource(source);
        if (colour != null) {
            ProductVariant v = new ProductVariant();
            v.setOptionColor(colour);
            i.setVariant(v);
        }
        return i;
    }

    @Test
    void theGeneralMarkSetsTheStartingPhotoAndEveryAiPhotoButNotAGroupsOwnOrAnUploadedOne() throws IOException {
        ProductImage base = photo(BASE, "Baby Pink", ProductImage.ImageSource.UPLOAD);
        ProductImage made = photo("https://x/hot.png", "Hot Pink PU30", ProductImage.ImageSource.AI_CLEANED);
        ProductImage black = photo("https://x/ca.png", "Black CA1538", ProductImage.ImageSource.AI_CLEANED);
        ProductImage uploaded = photo("https://x/own.png", "Sky Blue", ProductImage.ImageSource.UPLOAD);
        when(gemini.download(MASK)).thenReturn(capMask());
        when(images.findByProductIdInOrderBySortOrderAsc(anyList())).thenReturn(List.of(base, made, black, uploaded));

        var applied = service.apply(UUID.randomUUID(), MASK, BASE, null, List.of("black ca1538"));

        assertThat(applied.focus()).isEqualTo("50% 27%");
        assertThat(applied.photos()).isEqualTo(2);
        assertThat(base.getFocus()).isEqualTo("50% 27%");
        assertThat(made.getFocus()).isEqualTo("50% 27%");
        assertThat(black.getFocus()).isNull();
        assertThat(uploaded.getFocus()).isNull();
    }

    @Test
    void aGroupsOwnMarkSetsOnlyThatGroupsPhotos() throws IOException {
        ProductImage made = photo("https://x/hot.png", "Hot Pink PU30", ProductImage.ImageSource.AI_CLEANED);
        ProductImage black = photo("https://x/ca.png", "Black CA1538", ProductImage.ImageSource.AI_CLEANED);
        when(gemini.download(MASK)).thenReturn(capMask());
        when(images.findByProductIdInOrderBySortOrderAsc(anyList())).thenReturn(List.of(made, black));

        service.apply(UUID.randomUUID(), MASK, BASE, List.of("Black CA1538"), null);

        assertThat(black.getFocus()).isEqualTo("50% 27%");
        assertThat(made.getFocus()).isNull();
    }
}
