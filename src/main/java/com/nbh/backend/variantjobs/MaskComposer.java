package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Keeps a variant's photo the same as the product's photo everywhere except the part staff marked (owner, 2026-10-16:
 * the variants drifted from the main photo, with another background and a repainted bottle, when only the colour of the
 * cap should differ). The AI repaints the photo as it likes; this puts the product's own pixels back everywhere outside
 * the marked part, with a soft edge so the join does not show.
 *
 * <p>The mask is a picture the shape of the product's photo, white where the colour may change. If the three pictures
 * do not line up (the AI changed the shape of the photo, or the mask is for another photo) nothing is guessed: the AI's
 * photo is returned as it is, as if no part had been marked.
 */
public final class MaskComposer {

    private static final Logger log = LoggerFactory.getLogger(MaskComposer.class);

    /** How far the mask's shape may differ from the product's photo (width / height). */
    static final double MASK_ASPECT_TOLERANCE = 0.03;
    /** How far the AI's photo may differ from the product's before pasting back would no longer line up. */
    static final double AI_ASPECT_TOLERANCE = 0.06;
    /** A mask with nothing marked is no mask. */
    private static final float EMPTY = 0.01f;

    private MaskComposer() {}

    public static GeminiImageClient.Image apply(GeminiImageClient.Image source, GeminiImageClient.Image edited,
                                                GeminiImageClient.Image mask) {
        try {
            BufferedImage src = read(source);
            BufferedImage ai = read(edited);
            BufferedImage marked = read(mask);
            if (src == null || ai == null || marked == null) {
                log.info("Photo mask not used: a picture could not be read.");
                return edited;
            }
            if (!similarShape(src, marked, MASK_ASPECT_TOLERANCE) || !similarShape(src, ai, AI_ASPECT_TOLERANCE)) {
                log.info("Photo mask not used: the pictures are not the same shape ({}x{}, {}x{}, {}x{}).",
                        src.getWidth(), src.getHeight(), ai.getWidth(), ai.getHeight(),
                        marked.getWidth(), marked.getHeight());
                return edited;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            int[] base = pixels(src, w, h);
            int[] paint = pixels(ai, w, h);
            float[] m = maskValues(marked, w, h);
            float peak = 0;
            for (float v : m) {
                peak = Math.max(peak, v);
            }
            if (peak < EMPTY) {
                return edited;
            }
            soften(m, w, h, Math.max(2, Math.round(Math.max(w, h) * 0.006f)));

            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    row[x] = mix(base[i], paint[i], m[i]);
                }
                out.setRGB(0, y, w, 1, row, 0, w);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return new GeminiImageClient.Image(bytes.toByteArray(), "image/png");
        } catch (IOException | RuntimeException e) {
            log.warn("Photo mask not used: {}", e.getMessage());
            return edited;
        }
    }

    private static BufferedImage read(GeminiImageClient.Image image) throws IOException {
        return image == null || image.bytes() == null ? null : ImageIO.read(new ByteArrayInputStream(image.bytes()));
    }

    static boolean similarShape(BufferedImage a, BufferedImage b, double tolerance) {
        double ra = (double) a.getWidth() / a.getHeight();
        double rb = (double) b.getWidth() / b.getHeight();
        return Math.abs(ra - rb) / ra <= tolerance;
    }

    /** The picture drawn at w x h, as plain RGB over white (a transparent background reads as white). */
    private static int[] pixels(BufferedImage image, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return scaled.getRGB(0, 0, w, h, null, 0, w);
    }

    /** 0..1 for every pixel: how bright the mask is there, times how opaque (so white-on-clear and white-on-black both work). */
    private static float[] maskValues(BufferedImage marked, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(marked, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        int[] argb = scaled.getRGB(0, 0, w, h, null, 0, w);
        float[] out = new float[w * h];
        for (int i = 0; i < out.length; i++) {
            int p = argb[i];
            float a = ((p >>> 24) & 0xFF) / 255f;
            float lum = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255f;
            out[i] = a * lum;
        }
        return out;
    }

    /** Two passes of a box blur each way: a soft, even edge. */
    static void soften(float[] m, int w, int h, int radius) {
        float[] tmp = new float[m.length];
        for (int pass = 0; pass < 2; pass++) {
            boxAcross(m, tmp, w, h, radius);
            boxDown(tmp, m, w, h, radius);
        }
    }

    private static void boxAcross(float[] in, float[] out, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            double sum = 0;
            int count = 0;
            for (int x = 0; x <= Math.min(r, w - 1); x++) {
                sum += in[row + x];
                count++;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = (float) (sum / count);
                int add = x + r + 1;
                int drop = x - r;
                if (add < w) {
                    sum += in[row + add];
                    count++;
                }
                if (drop >= 0) {
                    sum -= in[row + drop];
                    count--;
                }
            }
        }
    }

    private static void boxDown(float[] in, float[] out, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            double sum = 0;
            int count = 0;
            for (int y = 0; y <= Math.min(r, h - 1); y++) {
                sum += in[y * w + x];
                count++;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = (float) (sum / count);
                int add = y + r + 1;
                int drop = y - r;
                if (add < h) {
                    sum += in[add * w + x];
                    count++;
                }
                if (drop >= 0) {
                    sum -= in[drop * w + x];
                    count--;
                }
            }
        }
    }

    private static int mix(int original, int painted, float m) {
        if (m <= 0f) {
            return original & 0xFFFFFF;
        }
        if (m >= 1f) {
            return painted & 0xFFFFFF;
        }
        int r = Math.round(((original >> 16) & 0xFF) * (1 - m) + ((painted >> 16) & 0xFF) * m);
        int g = Math.round(((original >> 8) & 0xFF) * (1 - m) + ((painted >> 8) & 0xFF) * m);
        int b = Math.round((original & 0xFF) * (1 - m) + (painted & 0xFF) * m);
        return (r << 16) | (g << 8) | b;
    }
}
