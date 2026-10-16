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

    @Test
    void onlyTheMarkedPartTakesTheAisPaintEverythingElseIsTheOriginal() throws Exception {
        GeminiImageClient.Image source = png(200, 300, Color.WHITE, Color.GRAY, 60, 120, 140, 260); // a "bottle"
        GeminiImageClient.Image ai = png(200, 300, Color.PINK, Color.MAGENTA, 60, 120, 140, 260);   // repainted everywhere
        GeminiImageClient.Image mask = png(200, 300, Color.BLACK, Color.WHITE, 70, 20, 130, 80);   // the "cap"

        BufferedImage out = decode(MaskComposer.apply(source, ai, mask));

        assertThat(out.getWidth()).isEqualTo(200);
        assertThat(out.getHeight()).isEqualTo(300);
        // Inside the marked part: the AI's.
        assertThat(dist(out.getRGB(100, 50), Color.PINK)).isLessThan(12);
        // Outside it, including where the AI repainted the bottle and the background: the original's.
        assertThat(dist(out.getRGB(10, 10), Color.WHITE)).isZero();
        assertThat(dist(out.getRGB(100, 200), Color.GRAY)).isZero();
        assertThat(dist(out.getRGB(190, 290), Color.WHITE)).isZero();
    }

    @Test
    void theEdgeIsSoftNotAHardCut() throws Exception {
        GeminiImageClient.Image source = png(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = png(200, 300, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image mask = png(200, 300, Color.BLACK, Color.WHITE, 70, 20, 130, 80);

        BufferedImage out = decode(MaskComposer.apply(source, ai, mask));

        // Walking out of the marked part the red fades to white instead of stopping at one pixel.
        int[] column = new int[12];
        for (int i = 0; i < column.length; i++) {
            column[i] = out.getRGB(130 + i, 50) & 0xFF; // blue channel: 0 in red, 255 in white
        }
        assertThat(column[0]).isLessThan(column[column.length - 1]);
        long between = java.util.Arrays.stream(column).filter(v -> v > 10 && v < 245).count();
        assertThat(between).isGreaterThan(1);
    }

    @Test
    void aMaskForAnotherShapeOfPhotoIsNotUsed() throws Exception {
        GeminiImageClient.Image source = png(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = png(200, 300, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image landscapeMask = png(300, 200, Color.BLACK, Color.WHITE, 10, 10, 50, 50);

        assertThat(MaskComposer.apply(source, ai, landscapeMask)).isSameAs(ai);
    }

    @Test
    void aPhotoTheAiReshapedIsNotPastedBackWronglyAndAnEmptyMaskChangesNothing() throws Exception {
        GeminiImageClient.Image source = png(200, 300, Color.WHITE, null, 0, 0, 0, 0);
        GeminiImageClient.Image squareAi = png(300, 300, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image mask = png(200, 300, Color.BLACK, Color.WHITE, 70, 20, 130, 80);
        GeminiImageClient.Image emptyMask = png(200, 300, Color.BLACK, null, 0, 0, 0, 0);
        GeminiImageClient.Image ai = png(200, 300, Color.RED, null, 0, 0, 0, 0);

        assertThat(MaskComposer.apply(source, squareAi, mask)).isSameAs(squareAi);
        assertThat(MaskComposer.apply(source, ai, emptyMask)).isSameAs(ai);
    }

    @Test
    void aPictureThatCannotBeReadFallsBackToTheAisPhoto() throws Exception {
        GeminiImageClient.Image ai = png(200, 300, Color.RED, null, 0, 0, 0, 0);
        GeminiImageClient.Image broken = new GeminiImageClient.Image(new byte[] {1, 2, 3}, "image/png");

        assertThat(MaskComposer.apply(broken, ai, broken)).isSameAs(ai);
    }
}
