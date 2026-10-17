package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Variant photos made without AI: only the marked part changes, to the variant's colour, with its light and shade. */
class CodeRecolourTest {

    private static final Color FOAM = new Color(222, 222, 226);
    private static final Color PINK_CAP = new Color(236, 170, 182);

    /** A grey backdrop with a pink cap (x 80-119, y 40-79) and a white highlight on it (x 95-99, y 45-49). */
    private static GeminiImageClient.Image photo() throws IOException {
        BufferedImage im = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(FOAM);
        g.fillRect(0, 0, 200, 300);
        g.setColor(PINK_CAP);
        g.fillRect(80, 40, 40, 40);
        g.setColor(Color.WHITE);
        g.fillRect(95, 45, 5, 5);
        g.dispose();
        return png(im);
    }

    private static GeminiImageClient.Image mask(int x0, int y0, int x1, int y1) throws IOException {
        BufferedImage im = new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 200, 300);
        g.setColor(Color.WHITE);
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        g.dispose();
        return png(im);
    }

    private static GeminiImageClient.Image png(BufferedImage im) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(im, "png", out);
        return new GeminiImageClient.Image(out.toByteArray(), "image/png");
    }

    private static BufferedImage decode(GeminiImageClient.Image image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(image.bytes()));
    }

    @Test
    void onlyTheMarkedPartTakesTheNewColourAndItsHighlightStaysBright() throws IOException {
        BufferedImage out = decode(CodeRecolour.apply(photo(), new CodeRecolour.Area(mask(80, 40, 120, 80), "#2E8B57")));

        Color cap = new Color(out.getRGB(110, 70));
        assertThat(cap.getGreen()).isGreaterThan(cap.getRed() + 40); // sea green now
        Color highlight = new Color(out.getRGB(97, 47));
        // The gloss stays: the highlight is still far brighter than the cap around it.
        assertThat(highlight.getRed() + highlight.getGreen() + highlight.getBlue())
                .isGreaterThan(cap.getRed() + cap.getGreen() + cap.getBlue() + 200);
        // Outside the mark: exactly the photo.
        assertThat(new Color(out.getRGB(20, 20))).isEqualTo(FOAM);
        assertThat(new Color(out.getRGB(100, 200))).isEqualTo(FOAM);
    }

    @Test
    void aGroupsAreaIsPaintedFirstAndTheCapOnTop() throws IOException {
        BufferedImage out = decode(CodeRecolour.apply(photo(),
                new CodeRecolour.Area(mask(60, 30, 140, 250), "#1A1A1A", false),
                new CodeRecolour.Area(mask(80, 40, 120, 80), "#C8102E")));

        Color body = new Color(out.getRGB(100, 200));
        assertThat(body.getRed() + body.getGreen() + body.getBlue()).isLessThan(150); // black bottle
        Color cap = new Color(out.getRGB(110, 70));
        assertThat(cap.getRed()).isGreaterThan(cap.getGreen() + 60); // red cap on top
    }

    @Test
    void aMarkSpillingOntoTheBackdropLeavesTheBackdropAlone() throws IOException {
        // marked well beyond the cap (x 70-129, y 30-89)
        BufferedImage out = decode(CodeRecolour.apply(photo(), new CodeRecolour.Area(mask(70, 30, 130, 90), "#2E8B57")));

        Color spill = new Color(out.getRGB(74, 34));
        assertThat(Math.abs(spill.getRed() - FOAM.getRed()) + Math.abs(spill.getGreen() - FOAM.getGreen())
                + Math.abs(spill.getBlue() - FOAM.getBlue())).isLessThan(12);
        Color cap = new Color(out.getRGB(110, 70));
        assertThat(cap.getGreen()).isGreaterThan(cap.getRed() + 40);
    }

    @Test
    void aColourWithoutACodeOrAnEmptyMarkIsRefused() {
        assertThatThrownBy(() -> CodeRecolour.apply(photo(), new CodeRecolour.Area(mask(80, 40, 120, 80), null)))
                .hasMessageContaining("colour code");
        assertThatThrownBy(() -> CodeRecolour.apply(photo(), new CodeRecolour.Area(mask(0, 0, 0, 0), "#C8102E")))
                .hasMessageContaining("Nothing was marked");
    }
}
