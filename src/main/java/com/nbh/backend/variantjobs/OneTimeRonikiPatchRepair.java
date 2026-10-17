package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ONE-TIME REPAIR, to be deleted (with {@link CodeRecolour}) once it has run in production (owner, 2026-10-17: "have this
 * done once in code and then later we can clear the code").
 *
 * <p>About 40 Roniki gel variant photos were made by an AI that moved the bottle and painted its own cream backdrop, so
 * pasting the product's photo back around the marked cap left a pale patch where the cap should be. The cause is fixed
 * in {@link VariantPhotoMaker} and {@link MaskComposer}; this mends the photos already made. For each variant: the patch
 * is found by comparing its photo with the product's own photo (the backdrop colour where the product's photo has
 * none), and there the cap is rebuilt from the product's own photo, recoloured to the variant's colour dot. The rest of
 * the variant's photo is left exactly as it is. The repaired photo is a new file; the variant's photo record points at
 * it, and the old file is kept (nothing is deleted).
 *
 * <p>Runs once, in the background after start-up, and records that in {@code one_time_task} (V67) so it never runs again.
 */
@Component
@Profile("!test")
public class OneTimeRonikiPatchRepair {

    private static final Logger log = LoggerFactory.getLogger(OneTimeRonikiPatchRepair.class);

    static final String TASK = "roniki-patch-repair-2026-10-17";
    static final String PRODUCT_SLUG = "roniki-gel";
    /** More of a photo than this showing the backdrop where the product's photo has none is a patch (damaged 0.33%, good 0.02%). */
    static final double PATCH_SHARE = 0.001;

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final ProductImageRepository images;
    private final VariantJobWriter writer;
    private final GeminiImageClient gemini;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig config;
    private final VariantJobRepository jobs;
    private final com.nbh.backend.catalog.repository.ProductVariantRepository variants;

    public OneTimeRonikiPatchRepair(JdbcTemplate jdbc, ProductRepository products, ProductImageRepository images,
                                    VariantJobWriter writer, GeminiImageClient gemini, CloudinaryUploader cloudinary,
                                    ImageCleanupConfig config, VariantJobRepository jobs,
                                    com.nbh.backend.catalog.repository.ProductVariantRepository variants) {
        this.jobs = jobs;
        this.variants = variants;
        this.jdbc = jdbc;
        this.products = products;
        this.images = images;
        this.writer = writer;
        this.gemini = gemini;
        this.cloudinary = cloudinary;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startInBackground() {
        Thread t = new Thread(this::runOnce, "one-time-roniki-repair");
        t.setDaemon(true);
        t.start();
    }

    void runOnce() {
        try {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM one_time_task WHERE name = ?", Integer.class, TASK);
            if (done != null && done > 0) return;
            if (!cloudinary.configured()) {
                log.info("One-time Roniki repair waits: Cloudinary isn't set up here.");
                return;
            }
            String result = repairAll(PRODUCT_SLUG);
            jdbc.update("INSERT INTO one_time_task (name, done_at, result) VALUES (?, now(), ?) ON CONFLICT (name) DO NOTHING",
                    TASK, result);
            log.info("One-time Roniki repair: {}", result);
        } catch (RuntimeException e) {
            // Not recorded as done: the next start tries again.
            log.warn("One-time Roniki repair did not finish: {}", e.getMessage(), e);
        }
    }

    String repairAll(String slug) {
        Product product = products.findAll().stream()
                .filter(p -> !p.isDeleted() && slug.equals(p.getSlug())).findFirst().orElse(null);
        if (product == null) return "no product " + slug;
        ProductVariant base = writer.base(product.getId(), null).orElse(null);
        VariantJobWriter.Source source = base == null ? null : writer.basePhoto(product.getId(), base.getId());
        if (source == null) return "no starting photo";
        GeminiImageClient.Image start = gemini.download(source.url());

        // Each live variant's main AI-made photo.
        Map<UUID, ProductVariant> live = new LinkedHashMap<>();
        for (ProductVariant v : variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId())) {
            live.put(v.getId(), v);
        }
        Map<UUID, ProductImage> made = new LinkedHashMap<>();
        for (ProductImage i : images.findByProductIdInOrderBySortOrderAsc(List.of(product.getId()))) {
            // Outside a database session the image's variant is only a reference: its id is known, nothing else is,
            // so the variants are looked up from the list loaded above (found live 2026-10-17: "no session").
            if (i.getVariant() == null || i.getSource() != ProductImage.ImageSource.AI_CLEANED) continue;
            if (!live.containsKey(i.getVariant().getId()) || i.getVariant().getId().equals(base.getId())) continue;
            ProductImage held = made.get(i.getVariant().getId());
            if (held == null || (i.isPrimary() && !held.isPrimary())) made.put(i.getVariant().getId(), i);
        }
        List<String> repaired = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ProductImage image : made.values()) {
            ProductVariant v = live.get(image.getVariant().getId());
            String name = v.getOptionColor() != null ? v.getOptionColor() : v.getVariantName();
            try {
                String hex = v.getSwatchHex();
                if (hex == null || hex.isBlank()) {
                    skipped.add(name + " (no colour code)");
                    continue;
                }
                VariantJobRequest job = jobOf(image.getUrl());
                if (job != null && job.guides().groupOf(name) != null && job.guides().groupOf(name).maskUrl() != null) {
                    skipped.add(name + " (in a group with its own marked part: redo it by hand)");
                    continue;
                }
                // The job's mark, only when it was marked on this starting photo.
                String maskUrl = job == null || job.photoGuide() == null ? null : job.photoGuide().maskUrl();
                String markedOn = job == null || job.photoGuide() == null ? null : job.photoGuide().maskPhotoUrl();
                if (markedOn != null && !markedOn.trim().equals(source.url())) maskUrl = null;
                GeminiImageClient.Image capMask = maskUrl == null || maskUrl.isBlank() ? null : gemini.download(maskUrl);
                GeminiImageClient.Image fixed = repair(start, gemini.download(image.getUrl()), hex,
                        config.getBackgroundHex(), capMask);
                if (fixed == null) continue; // no patch
                String publicId = v.getId() + "-repair-" + UUID.randomUUID().toString().substring(0, 6);
                String url = cloudinary.upload(fixed.bytes(), "products", publicId).url();
                image.setUrl(url);
                image.setThumbnailUrl(null);
                images.save(image);
                repaired.add(name);
            } catch (IOException | RuntimeException e) {
                skipped.add(name + " (" + e.getMessage() + ")");
            }
        }
        return "repaired " + repaired.size() + " of " + made.size() + " photos: " + repaired
                + (skipped.isEmpty() ? "" : "; skipped " + skipped);
    }

    /** The request of the job that made a photo, from the job id in its file name ("…-job-fc71a5a0-…"); null if unknown. */
    private VariantJobRequest jobOf(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-job-([0-9a-f]{8})-").matcher(url == null ? "" : url);
        if (!m.find()) return null;
        String prefix = m.group(1);
        return jobs.findAll().stream().filter(j -> j.getId().toString().startsWith(prefix)).findFirst()
                .map(writer::request).orElse(null);
    }

    /**
     * The variant's photo with its pale patch mended: where it shows the backdrop and the product's photo does not, the
     * product's photo recoloured to {@code hex} (its background left as it is); everywhere else unchanged. Null when the
     * photo has no patch.
     */
    public static GeminiImageClient.Image repair(GeminiImageClient.Image productPhoto, GeminiImageClient.Image damaged,
                                          String hex, String backdropHex) throws IOException {
        return repair(productPhoto, damaged, hex, backdropHex, null);
    }

    /**
     * @param capMask the part marked for the job that made the photo (the cap), when known: that whole part is rebuilt,
     *                so the cap comes out even. Without it, the outline of the patch is rebuilt.
     */
    public static GeminiImageClient.Image repair(GeminiImageClient.Image productPhoto, GeminiImageClient.Image damaged,
                                          String hex, String backdropHex, GeminiImageClient.Image capMask)
            throws IOException {
        BufferedImage b = MaskComposer.read(productPhoto);
        BufferedImage d = MaskComposer.read(damaged);
        Integer backdrop = MaskComposer.rgbOf(backdropHex);
        Integer target = MaskComposer.rgbOf(hex);
        if (b == null || d == null || backdrop == null || target == null
                || !MaskComposer.similarShape(b, d, MaskComposer.AI_ASPECT_TOLERANCE)) {
            return null;
        }
        int w = d.getWidth();
        int h = d.getHeight();
        int[] base = MaskComposer.draw(b, w, h, null).getRGB(0, 0, w, h, null, 0, w);
        int[] photo = MaskComposer.draw(d, w, h, null).getRGB(0, 0, w, h, null, 0, w);

        // Where the product's photo shows its plain background (the colour most of it shows) the variant's photo must
        // show the same, as it was pasted back from it; a pale patch is where it does not. Its colour varies (cream,
        // beige, peach), so it is found by the difference, not by its colour.
        double[] backgroundLab = medianLab(base);
        float[] patch = new float[w * h];
        long n = 0;
        for (int i = 0; i < patch.length; i++) {
            double[] b0 = MaskComposer.lab(base[i]);
            if (MaskComposer.deltaE(b0, backgroundLab) < BACKGROUND_NEAR
                    && MaskComposer.deltaE(MaskComposer.lab(photo[i]), b0) > PATCH_DIFFERENCE) {
                patch[i] = 1;
                n++;
            }
        }
        if (n < patch.length * PATCH_SHARE) return null;
        // The patch is the shape of the mark that was used: one solid blob. Where the product's own cap is a light pink
        // close to the cream, single pixels can't tell the two apart, so the blob is filled whole: the outline (convex
        // hull) of its largest piece, grown a little over its soft edge.
        BufferedImage marked = capMask == null ? null : MaskComposer.read(capMask);
        if (marked != null && MaskComposer.similarShape(b, marked, MaskComposer.MASK_ASPECT_TOLERANCE)) {
            // The whole marked cap, plus the patch where it spilled past it.
            float[] cap = MaskComposer.maskValues(marked, w, h);
            float[] blob = solidBlob(patch, w, h);
            for (int i = 0; i < patch.length; i++) patch[i] = Math.max(cap[i] > 0.5f ? 1f : 0f, blob[i]);
        } else {
            patch = solidBlob(patch, w, h);
        }
        MaskComposer.soften(patch, w, h, Math.max(2, Math.round(Math.max(w, h) * 0.008f)));
        for (int i = 0; i < patch.length; i++) patch[i] = patch[i] > 0.03f ? 1f : 0f;

        int[] fixed = base.clone();
        CodeRecolour.paint(fixed, base, patch.clone(), w, h, target, true);
        MaskComposer.soften(patch, w, h, Math.max(1, Math.round(Math.max(w, h) * 0.004f)));
        int[] out = new int[photo.length];
        for (int i = 0; i < out.length; i++) out[i] = MaskComposer.mix(photo[i], fixed[i], patch[i]);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return new GeminiImageClient.Image(bytes.toByteArray(), "image/png");
    }

    /** Within this colour difference of the photo's plain background a pixel of the product's photo is background. */
    static final double BACKGROUND_NEAR = 10;
    /** At least this different from the product's photo there, a background pixel of the variant's photo is the patch. */
    static final double PATCH_DIFFERENCE = 9;

    /** The median Lab colour of a photo, sampled: its plain background, which most of a product photo shows. */
    static double[] medianLab(int[] px) {
        int step = Math.max(1, px.length / 20000);
        List<double[]> s = new ArrayList<>();
        for (int i = 0; i < px.length; i += step) s.add(MaskComposer.lab(px[i]));
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            final int ch = c;
            out[c] = s.stream().mapToDouble(p -> p[ch]).sorted().skip(s.size() / 2).findFirst().orElse(0);
        }
        return out;
    }

    /** The convex outline of the largest connected piece of {@code m} (on 8-pixel cells), filled. */
    static float[] solidBlob(float[] m, int w, int h) {
        int cell = 8;
        int cw = (w + cell - 1) / cell;
        int ch = (h + cell - 1) / cell;
        boolean[] on = new boolean[cw * ch];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (m[y * w + x] > 0.5f) on[(y / cell) * cw + x / cell] = true;
        int[] label = new int[on.length];
        int best = 0, bestSize = 0, next = 0;
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int s = 0; s < on.length; s++) {
            if (!on[s] || label[s] != 0) continue;
            next++;
            int size = 0;
            label[s] = next;
            queue.add(s);
            while (!queue.isEmpty()) {
                int c = queue.poll();
                size++;
                int cx = c % cw, cy = c / cw;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue;
                        int nc = ny * cw + nx;
                        if (on[nc] && label[nc] == 0) {
                            label[nc] = next;
                            queue.add(nc);
                        }
                    }
            }
            if (size > bestSize) {
                bestSize = size;
                best = next;
            }
        }
        List<int[]> points = new ArrayList<>();
        for (int c = 0; c < label.length; c++) {
            if (label[c] != best) continue;
            int x0 = (c % cw) * cell, y0 = (c / cw) * cell;
            points.add(new int[] {x0, y0});
            points.add(new int[] {Math.min(w - 1, x0 + cell), y0});
            points.add(new int[] {x0, Math.min(h - 1, y0 + cell)});
            points.add(new int[] {Math.min(w - 1, x0 + cell), Math.min(h - 1, y0 + cell)});
        }
        List<int[]> hull = hull(points);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        java.awt.Polygon poly = new java.awt.Polygon();
        for (int[] p : hull) poly.addPoint(p[0], p[1]);
        g.fillPolygon(poly);
        g.dispose();
        float[] out = new float[w * h];
        byte[] px = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < out.length; i++) out[i] = (px[i] & 0xFF) > 127 ? 1f : 0f;
        return out;
    }

    /** Convex hull (monotone chain). */
    private static List<int[]> hull(List<int[]> pts) {
        pts.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<int[]> lower = new ArrayList<>();
        for (int[] p : pts) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0)
                lower.remove(lower.size() - 1);
            lower.add(p);
        }
        List<int[]> upper = new ArrayList<>();
        for (int i = pts.size() - 1; i >= 0; i--) {
            int[] p = pts.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0)
                upper.remove(upper.size() - 1);
            upper.add(p);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static long cross(int[] o, int[] a, int[] b) {
        return (long) (a[0] - o[0]) * (b[1] - o[1]) - (long) (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static boolean near(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF)) <= MaskComposer.BACKDROP_NEAR;
    }
}
