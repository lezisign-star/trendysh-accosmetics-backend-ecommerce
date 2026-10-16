package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.variantgen.VariantCombos;
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

    private static final String MAIN = "https://example.test/main.jpg";
    private static final String MASK = "https://example.test/mask.png";

    private final GeminiImageClient gemini = mock(GeminiImageClient.class);
    private final VariantPhotoMaker maker =
            new VariantPhotoMaker(gemini, new VariantPhotoPromptBuilder(), new ImageCleanupConfig());

    private PhotoGuides marked() {
        return new PhotoGuides(new VariantJobRequest.PhotoGuide("Only the cap shows the colour", null, MASK, MAIN),
                List.of(), List.of());
    }

    private Map<String, GeminiImageClient.Image> downloadedWithMask() throws IOException {
        Map<String, GeminiImageClient.Image> downloaded = new HashMap<>();
        downloaded.put(MASK, solid(200, 300, Color.BLACK, Color.WHITE, 70, 20, 130, 80));
        return downloaded;
    }

    @Test
    void withAMarkedPartTheVariantIsTheProductsPhotoExceptThere() throws Exception {
        GeminiImageClient.Image source = solid(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        when(gemini.edit(any(GeminiImageClient.Image.class), anyList(), anyString()))
                .thenReturn(solid(200, 300, Color.RED, null, 0, 0, 0, 0));

        VariantPhotoMaker.Made made = maker.recolour("Roniki Gel", source, MAIN, "Hot Pink PU30", "#CF4D88", null,
                marked(), downloadedWithMask());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(made.image().bytes()));

        assertThat(made.note()).isNull();
        assertThat(out.getRGB(100, 50) & 0xFF).isLessThan(40);          // the marked part: the AI's red
        assertThat(out.getRGB(20, 250) & 0xFFFFFF).isEqualTo(0xFFFFFF); // everywhere else: the original's white
    }

    @Test
    void aMarkMadeOnAnotherPhotoIsNotUsedAndStaffAreTold() throws Exception {
        GeminiImageClient.Image source = solid(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = solid(200, 300, Color.RED, null, 0, 0, 0, 0);
        when(gemini.edit(any(GeminiImageClient.Image.class), anyList(), anyString())).thenReturn(ai);

        VariantPhotoMaker.Made made = maker.recolour("Roniki Gel", source, "https://example.test/50ml.jpg",
                "Hot Pink PU30", "#CF4D88", null, marked(), downloadedWithMask());

        assertThat(made.image()).isSameAs(ai);
        assertThat(made.note()).contains("another photo");
    }

    @Test
    void withoutAMarkedPartTheAisPhotoIsUsedAsItAlwaysWas() throws Exception {
        GeminiImageClient.Image source = solid(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = solid(200, 300, Color.RED, null, 0, 0, 0, 0);
        when(gemini.edit(any(GeminiImageClient.Image.class), anyList(), anyString())).thenReturn(ai);

        VariantPhotoMaker.Made made = maker.recolour("Roniki Gel", source, MAIN, "Hot Pink PU30", "#CF4D88", null,
                PhotoGuides.none(), new HashMap<>());

        assertThat(made.image()).isSameAs(ai);
        assertThat(made.note()).isNull();
    }

    @Test
    void colourNamesAreTidiedTheSameWayAsTheWizardDoes() {
        // Keep in step with variantWizard.test.ts "a colour's name is tidied the same way for every variant".
        assertThat(VariantCombos.tidyColour("candy Colors 17 candy Colors 17")).isEqualTo("Candy Colors 17");
        assertThat(VariantCombos.tidyColour("Ice green 02 Ice green 02")).isEqualTo("Ice Green 02");
        assertThat(VariantCombos.tidyColour("  hot   pink  PU30 ")).isEqualTo("Hot Pink PU30");
        assertThat(VariantCombos.tidyColour("Rose Gold DH 03")).isEqualTo("Rose Gold DH 03");
        assertThat(VariantCombos.tidyColour("ZC26")).isEqualTo("ZC26");
        assertThat(VariantCombos.tidyColour("Pink Pink Panther")).isEqualTo("Pink Pink Panther");
    }
}
