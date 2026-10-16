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

/** The product's own pixels stay everywhere outside the part staff marked (owner, 2026-10-16). */
class MaskComposerTest {

    /** A white backdrop with a grey "bottle" and a "cap" on top, the whole picture moved right by {@code shift}. */
    private static GeminiImageClient.Image bottle(int w, int h, Color backdrop, Color body, Color cap, int shift)
            throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(backdrop);
        g.fillRect(0, 0, w, h);
        g.setColor(body);
        g.fillRect(60 + shift, 120, 80, 140);
        g.setColor(cap);
        g.fillRect(80 + shift, 40, 40, 80);
        g.dispose();
        return png(image);
    }

    private static GeminiImageClient.Image png(int w, int h, Color fill, Color box, int x0, int y0, int x1, int y1)
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
        return png(image);
    }

    private static GeminiImageClient.Image png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new GeminiImageClient.Image(bytes.toByteArray(), "image/png");
    }

    private static BufferedImage decode(GeminiImageClient.Image image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(image.bytes()));
    }

    private static int dist(int rgb, Color c) {
        return Math.abs(((rgb >> 16) & 0xFF) - c.getRed()) + Math.abs(((rgb >> 8) & 0xFF) - c.getGreen())
                + Math.abs((rgb & 0xFF) - c.getBlue());
    }

    private static final Color PALE_CAP = new Color(240, 190, 200);
    private static final Color CREAM = new Color(250, 238, 215);

    @Test
    void onlyTheMarkedPartTakesTheAisPaintEverythingElseIsTheOriginal() throws Exception {
        GeminiImageClient.Image source = bottle(200, 300, Color.WHITE, Color.GRAY, PALE_CAP, 0);
        GeminiImageClient.Image ai = bottle(200, 300, CREAM, Color.MAGENTA, Color.RED, 0); // repainted everywhere
        GeminiImageClient.Image mask = png(200, 300, Color.BLACK, Color.WHITE, 78, 38, 122, 122); // the cap

        MaskComposer.Result r = MaskComposer.apply(source, ai, mask);
        BufferedImage out = decode(r.image());

        assertThat(r.note()).isNull();
        assertThat(out.getWidth()).isEqualTo(200);
        assertThat(dist(out.getRGB(100, 80), Color.RED)).isLessThan(12);        // the cap: the AI's
        assertThat(dist(out.getRGB(10, 10), Color.WHITE)).isZero();            // backdrop: the original
        assertThat(dist(out.getRGB(100, 200), Color.GRAY)).isZero();           // bottle: the original
    }

    @Test
    void aProductTheAiMovedALittleIsLinedUpSoTheColourLandsOnTheCap() throws Exception {
        GeminiImageClient.Image source = bottle(200, 300, Color.WHITE, Color.GRAY, PALE_CAP, 0);
        // The AI moved everything 6 pixels right (and repainted the backdrop and bottle).
        GeminiImageClient.Image ai = bottle(200, 300, CREAM, Color.MAGENTA, Color.RED, 6);
        GeminiImageClient.Image mask = png(200, 300, Color.BLACK, Color.WHITE, 78, 38, 122, 122);

        BufferedImage out = decode(MaskComposer.apply(source, ai, mask).image());

        // Near the cap's left edge: without lining up this would be the AI's cream backdrop, not the red cap.
        assertThat(dist(out.getRGB(84, 80), Color.RED)).isLessThan(40);
        assertThat(dist(out.getRGB(100, 80), Color.RED)).isLessThan(12);
        assertThat(dist(out.getRGB(100, 200), Color.GRAY)).isZero();
    }

    @Test
    void theEdgeIsSoftNotAHardCut() throws Exception {
        GeminiImageClient.Image source = png(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = png(200, 300, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image mask = png(200, 300, Color.BLACK, Color.WHITE, 70, 20, 130, 80);

        BufferedImage out = decode(MaskComposer.apply(source, ai, mask).image());

        int[] column = new int[12];
        for (int i = 0; i < column.length; i++) {
            column[i] = out.getRGB(130 + i, 50) & 0xFF; // blue channel: 0 in red, 255 in white
        }
        assertThat(column[0]).isLessThan(column[column.length - 1]);
        long between = java.util.Arrays.stream(column).filter(v -> v > 10 && v < 245).count();
        assertThat(between).isGreaterThan(1);
    }

    @Test
    void aMaskForAnotherShapeOfPhotoIsNotUsedAndStaffAreTold() throws Exception {
        GeminiImageClient.Image source = png(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = png(200, 300, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image landscapeMask = png(300, 200, Color.BLACK, Color.WHITE, 10, 10, 50, 50);

        MaskComposer.Result r = MaskComposer.apply(source, ai, landscapeMask);
        assertThat(r.image()).isSameAs(ai);
        assertThat(r.note()).contains("another shape");
    }

    @Test
    void aPhotoTheAiReshapedIsNotPastedBackWronglyAndAnEmptyMaskChangesNothing() throws Exception {
        GeminiImageClient.Image source = png(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image squareAi = png(300, 300, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image mask = png(200, 300, Color.BLACK, Color.WHITE, 70, 20, 130, 80);
        GeminiImageClient.Image emptyMask = png(200, 300, Color.BLACK, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = png(200, 300, Color.RED, null, 0, 0, 0, 0);

        MaskComposer.Result reshaped = MaskComposer.apply(source, squareAi, mask);
        assertThat(reshaped.image()).isSameAs(squareAi);
        assertThat(reshaped.note()).contains("changed the shape");
        MaskComposer.Result empty = MaskComposer.apply(source, ai, emptyMask);
        assertThat(empty.image()).isSameAs(ai);
        assertThat(empty.note()).isNull();
    }

    @Test
    void aPictureThatCannotBeReadFallsBackToTheAisPhoto() throws Exception {
        GeminiImageClient.Image ai = png(200, 300, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image broken = new GeminiImageClient.Image(new byte[] {1, 2, 3}, "image/png");

        MaskComposer.Result r = MaskComposer.apply(broken, ai, broken);
        assertThat(r.image()).isSameAs(ai);
        assertThat(r.note()).isNotNull();
    }

    @Test
    void aVeryBigPhotoComesOutNoBiggerThanTheShopsOwn() throws Exception {
        GeminiImageClient.Image source = png(2400, 3600, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = png(1024, 1536, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image mask = png(240, 360, Color.BLACK, Color.WHITE, 100, 20, 140, 80);

        BufferedImage out = decode(MaskComposer.apply(source, ai, mask).image());
        assertThat(Math.max(out.getWidth(), out.getHeight())).isEqualTo(MaskComposer.MAX_EDGE);
    }
}
