package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Makes a variant's photo without AI (owner, 2026-10-17: the AI photos of Roniki gel came back with a pale patch where
 * the cap should be): the product's own photo, with the part staff marked recoloured to the variant's colour. Nothing
 * else in the photo can change, so every variant lines up exactly with the main photo.
 *
 * <p>The colour is set in CIE Lab: the new colour's lightness and hue, plus the photo's own light and shade inside the
 * marked part (how much lighter or darker each pixel is than the part's average), so the cap keeps its roundness and
 * gloss. Bright highlights keep their own brightness and lose most of their colour, as a real reflection does. A
 * glittery shade comes out as a smooth one with the photo's gloss.
 *
 * <p>An optional second area (a group's own mark, e.g. a black-bottle series' bottle) is painted first, with its own
 * colour; the general mark (the cap) is painted on top.
 */
public final class CodeRecolour {

    /** How much of the photo's own light and shade is kept inside the marked part. */
    static final double SHADE = 0.9;
    /** How much lighter than the part's average a pixel must be to count as a highlight (Lab lightness). */
    static final double HIGHLIGHT_FROM = 12;
    static final double HIGHLIGHT_SPAN = 25;

    private CodeRecolour() {}

    /**
     * One area to paint: its mask and the colour, "#RRGGBB". {@code spareBackground}: leave the background showing inside
     * the mark alone (for a cap, where a hand-painted mark spills onto the backdrop); off for a group's bottle area, as
     * a white bottle on a near-white backdrop would otherwise count as background and stay white.
     */
    public record Area(GeminiImageClient.Image mask, String hex, boolean spareBackground) {
        public Area(GeminiImageClient.Image mask, String hex) {
            this(mask, hex, true);
        }
    }

    /**
     * @param source the product's own photo
     * @param areas  painted in order (a group's area first, then the cap); each mask is the shape of {@code source}
     */
    public static GeminiImageClient.Image apply(GeminiImageClient.Image source, Area... areas) throws IOException {
        BufferedImage src = MaskComposer.read(source);
        if (src == null) throw new IllegalArgumentException("The product's photo could not be read.");
        double scale = Math.min(1.0, (double) MaskComposer.MAX_EDGE / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        int[] px = MaskComposer.draw(src, w, h, null).getRGB(0, 0, w, h, null, 0, w);
        // What is background is judged on the photo as it was, before any area is painted.
        int[] original = px.clone();

        for (Area area : areas) {
            if (area == null || area.mask() == null) continue;
            Integer target = MaskComposer.rgbOf(area.hex());
            if (target == null) throw new IllegalArgumentException("This colour has no colour code to paint with.");
            BufferedImage marked = MaskComposer.read(area.mask());
            if (marked == null) throw new IllegalArgumentException("The marked part could not be read.");
            if (!MaskComposer.similarShape(src, marked, MaskComposer.MASK_ASPECT_TOLERANCE)) {
                throw new IllegalArgumentException("The part was marked on a photo of another shape. Mark it again.");
            }
            float[] m = MaskComposer.maskValues(marked, w, h);
            paint(px, original, m, w, h, target, area.spareBackground());
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return new GeminiImageClient.Image(bytes.toByteArray(), "image/png");
    }

    /** Recolours {@code px} where {@code m} marks it, in place. */
    static void paint(int[] px, int[] original, float[] m, int w, int h, int target, boolean spareBackground) {
        // The part's average lightness, from the clearly marked pixels: the reference its light and shade are kept against.
        double sum = 0;
        long n = 0;
        for (int i = 0; i < px.length; i++) {
            if (m[i] >= 0.5f) {
                sum += MaskComposer.lab(px[i])[0];
                n++;
            }
        }
        if (n == 0) throw new IllegalArgumentException("Nothing was marked, so there is nothing to recolour.");
        double[] t = MaskComposer.lab(target);
        // A hand-painted mark spills a little onto the background around the cap: the background's colour, from a thin
        // ring just outside the mark, is left alone wherever it shows inside the mark.
        double[] around = spareBackground ? MaskComposer.ringColour(original, m, w, h) : null;
        float[] own = m.clone();
        if (around != null) {
            sum = 0;
            n = 0;
            for (int i = 0; i < px.length; i++) {
                if (m[i] <= 0.001f) continue;
                double object = clamp((MaskComposer.deltaE(MaskComposer.lab(original[i]), around) - BACKGROUND_NEAR) / BACKGROUND_SPAN, 0, 1);
                own[i] = (float) (m[i] * object);
                if (own[i] >= 0.5f) {
                    sum += MaskComposer.lab(px[i])[0];
                    n++;
                }
            }
        }
        if (n == 0) throw new IllegalArgumentException("The marked part shows only the background. Mark the product itself.");
        double mean = sum / n;
        m = own;
        MaskComposer.soften(m, w, h, Math.max(1, Math.round(Math.max(w, h) * 0.003f)));
        for (int i = 0; i < px.length; i++) {
            if (m[i] <= 0.001f) continue;
            double[] p = MaskComposer.lab(px[i]);
            double highlight = clamp((p[0] - mean - HIGHLIGHT_FROM) / HIGHLIGHT_SPAN, 0, 1);
            double l = (t[0] + (p[0] - mean) * SHADE) * (1 - highlight) + p[0] * highlight;
            double a = t[1] * (1 - 0.75 * highlight);
            double b = t[2] * (1 - 0.75 * highlight);
            px[i] = MaskComposer.mix(px[i], MaskComposer.rgb(clamp(l, 0, 100), a, b), m[i]);
        }
    }

    /** Within this colour difference (CIE76) of the background around the mark a pixel counts as background. */
    static final double BACKGROUND_NEAR = 8;
    static final double BACKGROUND_SPAN = 10;

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
