package com.nbh.backend.variantjobs;

import com.nbh.backend.imagecleanup.GeminiImageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
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
 * <p>The AI often moves or resizes the product by a few pixels. Before pasting, its photo is lined up with the
 * product's (a small shift and zoom, found by comparing the outlines outside the marked part), so the new colour lands
 * exactly where the old one was.
 *
 * <p>The mask is a picture the shape of the product's photo, white where the colour may change. If the pictures do not
 * line up (the AI changed the shape of the photo, or the mask is for another shape of photo) nothing is guessed: the
 * AI's photo is returned as it is, with a note saying why, so staff are told rather than left wondering.
 */
public final class MaskComposer {

    private static final Logger log = LoggerFactory.getLogger(MaskComposer.class);

    /** How far the mask's shape may differ from the product's photo (width / height). */
    static final double MASK_ASPECT_TOLERANCE = 0.03;
    /** How far the AI's photo may differ from the product's before pasting back would no longer line up. */
    static final double AI_ASPECT_TOLERANCE = 0.06;
    /** A mask with nothing marked is no mask. */
    private static final float EMPTY = 0.01f;
    /** The made photo is never bigger than the shop's own photos (the browser shrinks uploads to this). */
    static final int MAX_EDGE = 1600;
    /** Lining up is worked out on a small copy, this long on its longer side. */
    private static final int ALIGN_EDGE = 120;
    /** Shifts tried, in small-copy pixels each way (about 5 percent of the photo), and zooms tried. */
    private static final int MAX_SHIFT = 6;
    private static final double[] ZOOMS = {0.94, 0.95, 0.96, 0.97, 0.98, 0.99, 1.0, 1.01, 1.02, 1.03, 1.04, 1.05, 1.06};

    private MaskComposer() {}

    /**
     * More of the marked part than this showing the AI's own backdrop (where the product's photo shows none) means the AI
     * moved or reframed the product: pasted back, it leaves a pale patch where the cap should be (found live on Roniki,
     * 2026-10-17).
     */
    static final double MAX_BACKDROP_SHARE = 0.2;
    /** How close (sum of the red, green and blue differences) a pixel must be to the backdrop to count as backdrop. */
    static final int BACKDROP_NEAR = 36;

    /**
     * @param image  the photo to use
     * @param note   null when the marked part was used; otherwise why it was not, in staff's words
     * @param failed the AI's photo can't be used (the product moved inside the marked part): make it again or report it
     */
    public record Result(GeminiImageClient.Image image, String note, boolean failed) {
        public Result(GeminiImageClient.Image image, String note) {
            this(image, note, false);
        }
    }

    public static Result apply(GeminiImageClient.Image source, GeminiImageClient.Image edited, GeminiImageClient.Image mask) {
        return apply(source, edited, mask, null);
    }

    /**
     * @param backdropHex the backdrop the AI is known to paint ("#F3EBDD"); when it shows through the marked part where the
     *                    product's photo has none, the result is {@code failed}. Null skips the check.
     */
    public static Result apply(GeminiImageClient.Image source, GeminiImageClient.Image edited, GeminiImageClient.Image mask,
                               String backdropHex) {
        try {
            BufferedImage src = read(source);
            BufferedImage ai = read(edited);
            BufferedImage marked = read(mask);
            if (src == null || ai == null || marked == null) {
                return skipped(edited, "The marked part was not used: a picture could not be read.");
            }
            if (!similarShape(src, marked, MASK_ASPECT_TOLERANCE)) {
                return skipped(edited, "The marked part was not used: it was marked on a photo of another shape. Mark it again.");
            }
            if (!similarShape(src, ai, AI_ASPECT_TOLERANCE)) {
                return skipped(edited, "The marked part was not used: the AI changed the shape of the photo. Try this one again.");
            }
            double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            float[] m = maskValues(marked, w, h);
            float peak = 0;
            for (float v : m) {
                peak = Math.max(peak, v);
            }
            if (peak < EMPTY) {
                return new Result(edited, null);
            }
            BufferedImage base = draw(src, w, h, null);
            BufferedImage aiScaled = draw(ai, w, h, null);
            Fit fit = lineUp(base, aiScaled, m, w, h);
            BufferedImage aligned = fit.identity() ? aiScaled : draw(ai, w, h, fit.transform(w, h));

            int[] basePx = base.getRGB(0, 0, w, h, null, 0, w);
            int[] paintPx = aligned.getRGB(0, 0, w, h, null, 0, w);
            Integer backdrop = rgbOf(backdropHex);
            if (backdrop != null) {
                double share = backdropShare(basePx, paintPx, m, backdrop);
                if (share > MAX_BACKDROP_SHARE) {
                    log.info("AI photo rejected: {}% of the marked part shows its backdrop", Math.round(share * 100));
                    return new Result(edited,
                            "The AI moved the product, so its background showed inside the marked part.", true);
                }
            }
            soften(m, w, h, Math.max(2, Math.round(Math.max(w, h) * 0.006f)));

            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    row[x] = mix(basePx[i], paintPx[i], m[i]);
                }
                out.setRGB(0, y, w, 1, row, 0, w);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return new Result(new GeminiImageClient.Image(bytes.toByteArray(), "image/png"), null);
        } catch (IOException | RuntimeException e) {
            log.warn("Photo mask not used: {}", e.getMessage());
            return skipped(edited, "The marked part was not used: " + e.getMessage());
        }
    }

    /**
     * Where the marked part sits on the photo, as "x% y%" (the middle of what was painted), for the shop's cards to crop
     * around (V66). Null when nothing usable was marked or the mark can't be read.
     */
    public static String focusOf(GeminiImageClient.Image mask) {
        try {
            BufferedImage marked = read(mask);
            if (marked == null) return null;
            int w = Math.min(marked.getWidth(), ALIGN_EDGE * 2);
            int h = Math.max(1, (int) Math.round((double) marked.getHeight() * w / marked.getWidth()));
            float[] m = maskValues(marked, w, h);
            int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (m[y * w + x] < 0.5f) continue;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
            if (maxX < 0) return null;
            long cx = Math.round(100.0 * (minX + maxX + 1) / 2 / w);
            long cy = Math.round(100.0 * (minY + maxY + 1) / 2 / h);
            return cx + "% " + cy + "%";
        } catch (IOException | RuntimeException e) {
            log.info("The marked part's position could not be read: {}", e.getMessage());
            return null;
        }
    }

    /** "#F3EBDD" as 0xRRGGBB; null for nothing or anything else. */
    static Integer rgbOf(String hex) {
        if (hex == null) return null;
        String h = hex.trim().replace("#", "");
        if (!h.matches("[0-9A-Fa-f]{6}")) return null;
        return Integer.parseInt(h, 16);
    }

    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    /** Of the clearly marked pixels, the share where the AI shows the backdrop and the product's photo does not. */
    static double backdropShare(int[] basePx, int[] paintPx, float[] mask, int backdrop) {
        long marked = 0;
        long showing = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] < 0.5f) continue;
            marked++;
            if (distance(paintPx[i], backdrop) <= BACKDROP_NEAR && distance(basePx[i], backdrop) > BACKDROP_NEAR) {
                showing++;
            }
        }
        return marked == 0 ? 0 : (double) showing / marked;
    }

    /**
     * How much of a made photo shows the AI's backdrop where the product's photo has none (a pale patch left by an AI
     * that moved the product), as a share of the whole photo. For finding photos made before the check above existed.
     */
    public static double patchShare(GeminiImageClient.Image productPhoto, GeminiImageClient.Image made, String backdropHex)
            throws IOException {
        BufferedImage src = read(productPhoto);
        BufferedImage out = read(made);
        Integer backdrop = rgbOf(backdropHex);
        if (src == null || out == null || backdrop == null || !similarShape(src, out, AI_ASPECT_TOLERANCE)) return 0;
        int w = 240;
        int h = Math.max(1, (int) Math.round((double) src.getHeight() * w / src.getWidth()));
        int[] a = draw(src, w, h, null).getRGB(0, 0, w, h, null, 0, w);
        int[] b = draw(out, w, h, null).getRGB(0, 0, w, h, null, 0, w);
        long showing = 0;
        for (int i = 0; i < a.length; i++) {
            if (distance(b[i], backdrop) <= BACKDROP_NEAR && distance(a[i], backdrop) > BACKDROP_NEAR) showing++;
        }
        return (double) showing / a.length;
    }

    private static Result skipped(GeminiImageClient.Image edited, String note) {
        log.info(note);
        return new Result(edited, note);
    }

    static BufferedImage read(GeminiImageClient.Image image) throws IOException {
        return image == null || image.bytes() == null ? null : ImageIO.read(new ByteArrayInputStream(image.bytes()));
    }

    static boolean similarShape(BufferedImage a, BufferedImage b, double tolerance) {
        double ra = (double) a.getWidth() / a.getHeight();
        double rb = (double) b.getWidth() / b.getHeight();
        return Math.abs(ra - rb) / ra <= tolerance;
    }

    /** The picture drawn at w x h (moved by {@code at} when given), as plain RGB over white. */
    static BufferedImage draw(BufferedImage image, int w, int h, AffineTransform at) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (at != null) {
                g.transform(at);
            }
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 0..1 for every pixel: how bright the mask is there, times how opaque (so white-on-clear and white-on-black both work). */
    static float[] maskValues(BufferedImage marked, int w, int h) {
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

    // ------------------------------------------------------------------ lining up

    /** A zoom about the centre and a shift, both in full-size pixels; identity when nothing better was found. */
    record Fit(double zoom, double dx, double dy) {
        boolean identity() {
            return zoom == 1.0 && dx == 0 && dy == 0;
        }

        AffineTransform transform(int w, int h) {
            AffineTransform at = new AffineTransform();
            at.translate(w / 2.0 + dx, h / 2.0 + dy);
            at.scale(zoom, zoom);
            at.translate(-w / 2.0, -h / 2.0);
            return at;
        }
    }

    /**
     * Finds the zoom and shift that make the AI's photo match the product's best, comparing outlines (so a repainted
     * background or bottle colour does not mislead it) outside the marked part only (that part is meant to differ).
     */
    static Fit lineUp(BufferedImage base, BufferedImage ai, float[] mask, int w, int h) {
        double f = (double) ALIGN_EDGE / Math.max(w, h);
        int sw = Math.max(8, (int) Math.round(w * f));
        int sh = Math.max(8, (int) Math.round(h * f));
        float[] gb = edges(gray(draw(base, sw, sh, null), sw, sh), sw, sh);
        float[] ga = edges(gray(draw(ai, sw, sh, null), sw, sh), sw, sh);
        float[] weight = outsideMask(mask, w, h, sw, sh);

        double best = cost(gb, ga, weight, sw, sh, 1.0, 0, 0);
        double identity = best;
        double bz = 1.0;
        int bx = 0;
        int by = 0;
        for (double z : ZOOMS) {
            for (int dy = -MAX_SHIFT; dy <= MAX_SHIFT; dy++) {
                for (int dx = -MAX_SHIFT; dx <= MAX_SHIFT; dx++) {
                    if (z == 1.0 && dx == 0 && dy == 0) continue;
                    double c = cost(gb, ga, weight, sw, sh, z, dx, dy);
                    if (c < best) {
                        best = c;
                        bz = z;
                        bx = dx;
                        by = dy;
                    }
                }
            }
        }
        // Only move it when that is clearly better: noise must not nudge a photo that was already in place.
        if (best > identity * 0.97) {
            return new Fit(1.0, 0, 0);
        }
        return new Fit(bz, bx / f, by / f);
    }

    /** Mean difference of outlines where the AI's photo, zoomed by z and shifted, is compared with the product's. */
    private static double cost(float[] gb, float[] ga, float[] weight, int w, int h, double z, int dx, int dy) {
        double cx = w / 2.0;
        double cy = h / 2.0;
        double sum = 0;
        double wsum = 0;
        for (int y = 0; y < h; y++) {
            double sy = (y - cy - dy) / z + cy;
            int iy = (int) Math.round(sy);
            if (iy < 0 || iy >= h) continue;
            for (int x = 0; x < w; x++) {
                float wt = weight[y * w + x];
                if (wt <= 0) continue;
                double sx = (x - cx - dx) / z + cx;
                int ix = (int) Math.round(sx);
                if (ix < 0 || ix >= w) continue;
                sum += wt * Math.abs(gb[y * w + x] - ga[iy * w + ix]);
                wsum += wt;
            }
        }
        return wsum == 0 ? Double.MAX_VALUE : sum / wsum;
    }

    private static float[] gray(BufferedImage image, int w, int h) {
        int[] px = image.getRGB(0, 0, w, h, null, 0, w);
        float[] out = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            out[i] = 0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF);
        }
        return out;
    }

    private static float[] edges(float[] g, int w, int h) {
        float[] out = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                out[i] = Math.abs(g[i + 1] - g[i - 1]) + Math.abs(g[i + w] - g[i - w]);
            }
        }
        return out;
    }

    /** 1 where the photos should match (well away from the marked part), 0 inside it and just around it. */
    private static float[] outsideMask(float[] mask, int w, int h, int sw, int sh) {
        float[] out = new float[sw * sh];
        int rx = Math.max(1, w / sw);
        int ry = Math.max(1, h / sh);
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int x0 = Math.min(w - 1, (int) ((long) x * w / sw));
                int y0 = Math.min(h - 1, (int) ((long) y * h / sh));
                float max = 0;
                // The marked part grown by about two small-copy pixels: its edge is meant to differ too.
                for (int yy = Math.max(0, y0 - 2 * ry); yy <= Math.min(h - 1, y0 + 3 * ry); yy += Math.max(1, ry / 2)) {
                    for (int xx = Math.max(0, x0 - 2 * rx); xx <= Math.min(w - 1, x0 + 3 * rx); xx += Math.max(1, rx / 2)) {
                        max = Math.max(max, mask[yy * w + xx]);
                    }
                }
                out[y * sw + x] = max > 0.05f ? 0f : 1f;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ soft edge and mixing

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

    static int mix(int original, int painted, float m) {
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
