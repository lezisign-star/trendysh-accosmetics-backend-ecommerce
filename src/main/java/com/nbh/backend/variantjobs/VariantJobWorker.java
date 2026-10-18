package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.imagecleanup.WatermarkService;
import com.nbh.backend.variantgen.VariantCombos;
import com.nbh.backend.variantgen.VariantPhotoPromptBuilder;
import com.nbh.backend.variantgen.VariantPlanner;
import com.nbh.backend.variantjobs.VariantJob.Stage;
import com.nbh.backend.variantjobs.VariantJobItem.StepStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Runs one claimed job through its stages (VARIANTS_PLAN.md 3.3): create the variants, write their descriptions, make
 * their photos. Every stage only does what is still PENDING, so a job taken over after a restart or a quota pause carries
 * on rather than starting again. A failed description or photo never stops the rest; the quota pauses the whole job,
 * which the poller picks up again later by itself.
 */
@Slf4j
@Service
public class VariantJobWorker {

    private final VariantJobWriter writer;
    private final VariantJobItemRepository items;
    private final VariantJobRepository jobs;
    private final ProductRepository products;
    private final VariantPlanner planner;
    private final VariantPhotoPromptBuilder prompts;
    private final GeminiImageClient gemini;
    private final CloudinaryUploader cloudinary;
    private final WatermarkService watermark;
    private final ImageCleanupConfig config;
    private final Duration quotaPause;
    private final VariantPhotoMaker maker;

    public VariantJobWorker(VariantJobWriter writer, VariantJobItemRepository items, VariantJobRepository jobs,
                            ProductRepository products, VariantPlanner planner, VariantPhotoPromptBuilder prompts,
                            GeminiImageClient gemini, CloudinaryUploader cloudinary, WatermarkService watermark,
                            ImageCleanupConfig config, VariantPhotoMaker maker,
                            @Value("${app.variant-jobs.quota-pause-minutes:15}") long quotaPauseMinutes) {
        this.maker = maker;
        this.writer = writer;
        this.items = items;
        this.jobs = jobs;
        this.products = products;
        this.planner = planner;
        this.prompts = prompts;
        this.gemini = gemini;
        this.cloudinary = cloudinary;
        this.watermark = watermark;
        this.config = config;
        this.quotaPause = Duration.ofMinutes(quotaPauseMinutes);
    }

    /** Thrown inside a stage when the job must stop for now (quota) or for good (cancelled). */
    private static final class Stop extends RuntimeException {
        private Stop() {
            super(null, null, false, false);
        }
    }

    public void run(UUID jobId) {
        try {
            VariantJob job = jobs.findById(jobId).orElseThrow();
            if (job.getStage() == Stage.CREATE) {
                if (writer.cancelRequested(jobId)) {
                    writer.finish(jobId);
                    return;
                }
                writer.createAll(jobId);
            }
            job = jobs.findById(jobId).orElseThrow();
            VariantJobRequest request = writer.request(job);
            Product product = products.findById(job.getProductId()).orElseThrow();
            if (job.getStage() == Stage.DESCRIPTIONS) {
                descriptions(job, request, product);
                writer.setStage(jobId, Stage.PHOTOS);
            }
            photos(jobId, request, product);
            writer.finish(jobId);
        } catch (Stop stop) {
            // paused or cancelled: already recorded
        } catch (GeminiException e) {
            if (e.isQuotaExceeded()) {
                writer.pause(jobId, Instant.now().plus(quotaPause), "The AI quota was reached. It carries on by itself later.");
            } else {
                writer.fail(jobId, e.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("Variant job {} stopped", jobId, e);
            writer.fail(jobId, "Stopped with an error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- descriptions

    private void descriptions(VariantJob job, VariantJobRequest request, Product product) {
        List<VariantJobItem> pending = items.findByJobIdOrderByPositionAsc(job.getId()).stream()
                .filter(i -> i.getVariantId() != null && i.getDescriptionStatus() == StepStatus.PENDING).toList();
        if (pending.isEmpty()) {
            return;
        }
        GeminiImageClient.Image photo = null;
        VariantJobWriter.Source source = writer.basePhoto(product.getId(), baseId(job, request));
        if (source != null) {
            try {
                photo = gemini.download(source.url());
            } catch (GeminiException e) {
                log.info("Variant job {}: writing descriptions without the photo: {}", job.getId(), e.getMessage());
            }
        }
        String description = product.getDescription();
        for (int from = 0; from < pending.size(); from += VariantJobValidator.DESCRIPTION_CHUNK) {
            checkCancel(job.getId());
            List<VariantJobItem> part = pending.subList(from, Math.min(pending.size(), from + VariantJobValidator.DESCRIPTION_CHUNK));
            List<VariantCombos.Combo> combos = part.stream().map(i -> new VariantCombos.Combo(i.getColor(), i.getSize())).toList();
            List<VariantPlanner.Details> details;
            try {
                details = planner.details(product.getName(), description, request.prompt(), combos, photo);
            } catch (GeminiException e) {
                if (e.isQuotaExceeded()) {
                    writer.pause(job.getId(), Instant.now().plus(quotaPause),
                            "The AI quota was reached while writing descriptions. It carries on by itself later.");
                    throw new Stop();
                }
                details = combos.stream().map(c -> new VariantPlanner.Details(null, null)).toList();
            }
            Map<UUID, String> byItem = new LinkedHashMap<>();
            for (int i = 0; i < part.size(); i++) {
                byItem.put(part.get(i).getId(), details.get(i).description());
            }
            writer.saveDescriptions(job.getId(), byItem);
        }
    }

    // ---------------------------------------------------------------- photos

    /**
     * A colour master once made in this run: the raw image (for label edits) and its stored file (for the variants that
     * use it as their photo). The file is only stored when a variant actually uses the master as its photo: a variant
     * whose size has label text gets its own edited photo, and the master is then only a means to it. So every stored
     * photo of a job is some variant's one photo; none is left over.
     */
    private static final class MasterImage {
        private final GeminiImageClient.Image image;
        private String url;
        private final boolean madeByAi;

        MasterImage(GeminiImageClient.Image image, String url, boolean madeByAi) {
            this.image = image;
            this.url = url;
            this.madeByAi = madeByAi;
        }

        GeminiImageClient.Image image() {
            return image;
        }

        String url() {
            return url;
        }

        boolean madeByAi() {
            return madeByAi;
        }
    }

    private void photos(UUID jobId, VariantJobRequest request, Product product) {
        VariantJob job = jobs.findById(jobId).orElseThrow();
        List<VariantJobItem> pending = items.findByJobIdOrderByPositionAsc(jobId).stream()
                .filter(i -> i.getVariantId() != null && i.getPhotoStatus() == StepStatus.PENDING).toList();
        if (pending.isEmpty()) {
            return;
        }
        Map<String, VariantJobRequest.SizeIn> sizeInfo = new HashMap<>();
        for (VariantJobRequest.SizeIn s : VariantJobWriter.nn(request.sizes())) {
            sizeInfo.put(s.name() == null ? "" : s.name().trim().toLowerCase(Locale.ROOT), s);
        }
        PhotoPlan plan = PhotoPlan.of(
                pending.stream().map(i -> new PhotoPlan.Want(i.getColor(), i.getSize())).toList(),
                size -> {
                    VariantJobRequest.SizeIn s = sizeInfo.get(size.trim().toLowerCase(Locale.ROOT));
                    return s == null ? null : s.photoImageId();
                },
                size -> {
                    VariantJobRequest.SizeIn s = sizeInfo.get(size.trim().toLowerCase(Locale.ROOT));
                    return s == null || s.labelText() == null || s.labelText().isBlank() ? null : s.labelText().trim();
                });

        VariantJobWriter.Source base = writer.basePhoto(product.getId(), baseId(job, request));
        Set<UUID> sizePhotoIds = new HashSet<>();
        plan.steps().forEach(s -> {
            if (s.master().sourcePhotoId() != null) sizePhotoIds.add(s.master().sourcePhotoId());
        });
        Map<UUID, String> sizePhotoUrls = writer.imageUrls(sizePhotoIds);

        Map<String, GeminiImageClient.Image> downloaded = new HashMap<>();
        PhotoGuides guides = request.guides();
        // A "Redo photos" job replaces the AI-made photos it is redoing instead of keeping them behind the new one.
        boolean replace = request.isRedo();
        // Staff may have given a variant a photo of their own while it waited (an AI quota pause lasts a quarter of an
        // hour; a retry can sit in the queue): no second photo is made to go on top of theirs. Not for "Redo photos",
        // whose variants have photos by design.
        Set<UUID> fixedByHand = replace ? Set.of()
                : writer.removedOrWithOwnPhoto(pending.stream().map(VariantJobItem::getVariantId).toList());
        Map<PhotoPlan.Master, MasterImage> masters = new HashMap<>();
        Map<PhotoPlan.Master, String> masterFailures = new HashMap<>();

        for (VariantJobItem item : pending) {
            checkCancel(jobId);
            writer.heartbeat(jobId);
            if (fixedByHand.contains(item.getVariantId())) {
                writer.photoSkipped(jobId, item.getId());
                continue;
            }
            PhotoPlan.Step step = plan.stepFor(item.getColor(), item.getSize());
            try {
                if (masterFailures.containsKey(step.master())) {
                    writer.photoFailed(jobId, item.getId(), masterFailures.get(step.master()));
                    continue;
                }
                MasterImage master = masters.get(step.master());
                // A colour staff tried and approved before Generate: that photo is the colour's photo, no new call.
                String approved = master == null && step.master().needsAi() && step.master().sourcePhotoId() == null
                        ? guides.trialFor(item.getColor()) : null;
                if (approved != null) {
                    writer.heartbeat(jobId);
                    master = new MasterImage(null, approved, true); // fetched only if a label edit needs it
                    masters.put(step.master(), master);
                }
                if (master == null) {
                    String sourceUrl = step.master().sourcePhotoId() != null
                            ? sizePhotoUrls.get(step.master().sourcePhotoId()) : (base == null ? null : base.url());
                    if (sourceUrl == null) {
                        String why = "There was no photo to make this one from. Add a photo to the product and retry.";
                        masterFailures.put(step.master(), why);
                        writer.photoFailed(jobId, item.getId(), why);
                        continue;
                    }
                    // Every call that can take a minute or more is preceded by a heartbeat, so a job is only
                    // taken over when its worker has really gone (see stale-heartbeat-minutes).
                    GeminiImageClient.Image source = downloaded.get(sourceUrl);
                    if (source == null) {
                        writer.heartbeat(jobId);
                        source = gemini.download(sourceUrl);
                        downloaded.put(sourceUrl, source);
                    }
                    if (step.master().needsAi()) {
                        writer.heartbeat(jobId);
                        VariantPhotoMaker.Made made = maker.recolour(product.getName(), source, sourceUrl,
                                item.getColor(), VariantJobWriter.hexOf(request, item.getColor()), request.prompt(), guides,
                                downloaded);
                        if (made.note() != null) {
                            log.info("Job {} {}: {}", jobId, item.getColor(), made.note());
                        }
                        GeminiImageClient.Image recoloured = made.image();
                        // Not stored yet: only if a variant ends up using the master itself (below).
                        master = new MasterImage(recoloured, null, true);
                    } else {
                        master = new MasterImage(source, sourceUrl, false);
                    }
                    masters.put(step.master(), master);
                }
                // Where the marked part sits, so the shop's cards crop around it (V66): on the new photo, and on the
                // photo it was made from (the mark was painted there).
                String madeFrom = step.master().sourcePhotoId() != null
                        ? sizePhotoUrls.get(step.master().sourcePhotoId()) : (base == null ? null : base.url());
                String focus = master.madeByAi() ? maker.focusFor(item.getColor(), madeFrom, guides, downloaded) : null;
                if (focus != null) {
                    writer.focusPhoto(product.getId(), madeFrom, focus);
                }
                if (step.labelEdit()) {
                    writer.heartbeat(jobId);
                    GeminiImageClient.Image from = master.image() != null ? master.image() : maker.download(master.url(), downloaded);
                    GeminiImageClient.Image relabelled = gemini.edit(from,
                            prompts.labelOnly(product.getName(), step.labelText(), config.getBackgroundHex()));
                    writer.attachPhoto(jobId, item.getId(), upload(relabelled, item.getVariantId(), jobId), true, replace,
                            focus);
                } else {
                    if (master.url() == null) {
                        // The first variant to use this master stores it; the others of the colour share the file.
                        master.url = upload(master.image(), item.getVariantId(), jobId);
                    }
                    writer.attachPhoto(jobId, item.getId(), master.url(), master.madeByAi(), replace, focus);
                }
            } catch (GeminiException e) {
                if (e.isQuotaExceeded()) {
                    writer.pause(jobId, Instant.now().plus(quotaPause),
                            "The AI quota was reached while making photos. It carries on by itself later.");
                    throw new Stop();
                }
                if (!masters.containsKey(step.master())) {
                    masterFailures.put(step.master(), e.getMessage());
                }
                writer.photoFailed(jobId, item.getId(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Variant job {}: photo for item {} failed", jobId, item.getId(), e);
                writer.photoFailed(jobId, item.getId(), "The photo could not be made or stored: " + e.getMessage());
            }
        }
    }

    private String upload(GeminiImageClient.Image image, UUID variantId, UUID jobId) {
        writer.heartbeat(jobId);
        String publicId = variantId + "-job-" + jobId.toString().substring(0, 8) + "-" + UUID.randomUUID().toString().substring(0, 6);
        return cloudinary.upload(watermark.stamp(image.bytes()), "products", publicId).url();
    }

    private void checkCancel(UUID jobId) {
        if (writer.cancelRequested(jobId)) {
            writer.finish(jobId);
            throw new Stop();
        }
    }

    private UUID baseId(VariantJob job, VariantJobRequest request) {
        return writer.base(job.getProductId(), request.baseVariantId()).map(v -> v.getId()).orElse(null);
    }
}
