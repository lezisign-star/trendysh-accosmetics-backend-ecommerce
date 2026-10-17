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
                sum += lab(px[i])[0];
                n++;
            }
        }
        if (n == 0) throw new IllegalArgumentException("Nothing was marked, so there is nothing to recolour.");
        double[] t = lab(target);
        // A hand-painted mark spills a little onto the background around the cap: the background's colour, from a thin
        // ring just outside the mark, is left alone wherever it shows inside the mark.
        double[] around = spareBackground ? ringColour(original, m, w, h) : null;
        float[] own = m.clone();
        if (around != null) {
            sum = 0;
            n = 0;
            for (int i = 0; i < px.length; i++) {
                if (m[i] <= 0.001f) continue;
                double object = clamp((deltaE(lab(original[i]), around) - BACKGROUND_NEAR) / BACKGROUND_SPAN, 0, 1);
                own[i] = (float) (m[i] * object);
                if (own[i] >= 0.5f) {
                    sum += lab(px[i])[0];
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
            double[] p = lab(px[i]);
            double highlight = clamp((p[0] - mean - HIGHLIGHT_FROM) / HIGHLIGHT_SPAN, 0, 1);
            double l = (t[0] + (p[0] - mean) * SHADE) * (1 - highlight) + p[0] * highlight;
            double a = t[1] * (1 - 0.75 * highlight);
            double b = t[2] * (1 - 0.75 * highlight);
            px[i] = MaskComposer.mix(px[i], rgb(clamp(l, 0, 100), a, b), m[i]);
        }
    }

    /** Within this colour difference (CIE76) of the background around the mark a pixel counts as background. */
    static final double BACKGROUND_NEAR = 8;
    static final double BACKGROUND_SPAN = 10;

    /** The average Lab colour of a thin ring just outside the mark; null when there is no such ring. */
    static double[] ringColour(int[] px, float[] m, int w, int h) {
        float[] grown = m.clone();
        MaskComposer.soften(grown, w, h, Math.max(2, Math.round(Math.max(w, h) * 0.012f)));
        double l = 0, a = 0, b = 0;
        long n = 0;
        for (int i = 0; i < px.length; i++) {
            if (m[i] < 0.02f && grown[i] > 0.02f) {
                double[] p = lab(px[i]);
                l += p[0];
                a += p[1];
                b += p[2];
                n++;
            }
        }
        return n < 20 ? null : new double[] {l / n, a / n, b / n};
    }

    static double deltaE(double[] p, double[] q) {
        double dl = p[0] - q[0], da = p[1] - q[1], db = p[2] - q[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    // ------------------------------------------------------------------ sRGB <-> CIE Lab (D65)

    static double[] lab(int rgb) {
        double r = linear(((rgb >> 16) & 0xFF) / 255.0);
        double g = linear(((rgb >> 8) & 0xFF) / 255.0);
        double bl = linear((rgb & 0xFF) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * bl) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * bl;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * bl) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    static int rgb(double l, double a, double b) {
        double fy = (l + 16) / 116;
        double fx = fy + a / 500;
        double fz = fy - b / 200;
        double x = fInv(fx) * 0.95047;
        double y = fInv(fy);
        double z = fInv(fz) * 1.08883;
        double r = 3.2406 * x - 1.5372 * y - 0.4986 * z;
        double g = -0.9689 * x + 1.8758 * y + 0.0415 * z;
        double bl = 0.0557 * x - 0.2040 * y + 1.0570 * z;
        return (channel(r) << 16) | (channel(g) << 8) | channel(bl);
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int channel(double c) {
        double s = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(Math.max(c, 0), 1 / 2.4) - 0.055;
        return (int) Math.round(clamp(s, 0, 1) * 255);
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    private static double fInv(double t) {
        double t3 = t * t * t;
        return t3 > 216.0 / 24389 ? t3 : (116 * t - 16) / (24389.0 / 27);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
