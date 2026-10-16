package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.variantgen.VariantPhotoPromptBuilder;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A marked part is honoured by the photo maker the trial and the job both use (owner, 2026-10-16). */
class VariantPhotoMakerMaskTest {

    private static GeminiImageClient.Image solid(int w, int h, Color fill, Color box, int x0, int y0, int x1, int y1)
            throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(fill);
        g.fillRect(0, 0, w, h);
        if (box != null) {
            g.setColor(box);
            g.fillRect(x0, y0, x1 - x0, y1 - y0);
        }
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new GeminiImageClient.Image(bytes.toByteArray(), "image/png");
    }

    private final GeminiImageClient gemini = mock(GeminiImageClient.class);
    private final VariantPhotoMaker maker =
            new VariantPhotoMaker(gemini, new VariantPhotoPromptBuilder(), new ImageCleanupConfig());

    @Test
    void withAMarkedPartTheVariantIsTheProductsPhotoExceptThere() throws Exception {
        GeminiImageClient.Image source = solid(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = solid(200, 300, Color.RED, null, 0, 0, 0, 0); // repainted everywhere
        GeminiImageClient.Image mask = solid(200, 300, Color.BLACK, Color.WHITE, 70, 20, 130, 80);
        when(gemini.edit(any(GeminiImageClient.Image.class), anyList(), anyString())).thenReturn(ai);
        Map<String, GeminiImageClient.Image> downloaded = new HashMap<>();
        downloaded.put("https://example.test/mask.png", mask);

        PhotoGuides guides = new PhotoGuides(
                new VariantJobRequest.PhotoGuide("Only the cap shows the colour", null, "https://example.test/mask.png"),
                List.of(), List.of());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(
                maker.recolour("Roniki Gel", source, "Hot Pink PU30", "#CF4D88", null, guides, downloaded).bytes()));

        assertThat(out.getRGB(100, 50) & 0xFF).isLessThan(40);          // the marked part: the AI's red
        assertThat(out.getRGB(20, 250) & 0xFFFFFF).isEqualTo(0xFFFFFF); // everywhere else: the original's white
    }

    @Test
    void withoutAMarkedPartTheAisPhotoIsUsedAsItAlwaysWas() throws Exception {
        GeminiImageClient.Image source = solid(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = solid(200, 300, Color.RED, null, 0, 0, 0, 0);
        when(gemini.edit(any(GeminiImageClient.Image.class), anyList(), anyString())).thenReturn(ai);

        GeminiImageClient.Image made = maker.recolour("Roniki Gel", source, "Hot Pink PU30", "#CF4D88", null,
                PhotoGuides.none(), new HashMap<>());

        assertThat(made).isSameAs(ai);
    }
}
