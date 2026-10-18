package com.nbh.backend.variantjobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.ProductOptionsService;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.variantjobs.VariantJob.Stage;
import com.nbh.backend.variantjobs.VariantJob.Status;
import com.nbh.backend.variantjobs.VariantJobItem.StepStatus;
import com.nbh.backend.variantjobs.VariantJobValidator.Plan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** What the variant wizard and the Jobs screens call (VARIANTS_PLAN.md 3.4). */
@Service
public class VariantJobService {

    public record JobDto(UUID id, UUID productId, String productName, Status status, Stage stage, boolean approveFirst,
                         List<String> summary, int variantsTotal, int variantsCreated, int descriptionsDone,
                         int descriptionsFailed, int photosDone, int photosFailed, String lastError,
                         Instant nextAttemptAt, UUID createdBy, String createdByName, Instant createdAt,
                         Instant startedAt, Instant finishedAt, boolean redo) {}

    public record ItemDto(UUID id, int position, UUID variantId, String color, String size, String label,
                          BigDecimal price, String sku, Boolean live, String imageUrl, String swatchHex,
                          StepStatus descriptionStatus, StepStatus photoStatus, String error) {}

    public record JobDetailDto(JobDto job, List<ItemDto> items) {}

    private final VariantJobRepository jobs;
    private final VariantJobItemRepository items;
    private final VariantJobWriter writer;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final StaffUserRepository staff;
    private final ImageCleanupConfig config;
    private final CloudinaryUploader cloudinary;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final ProductOptionsService productOptions;

    public VariantJobService(VariantJobRepository jobs, VariantJobItemRepository items, VariantJobWriter writer,
                             ProductRepository products, ProductVariantRepository variants, ProductImageRepository images,
                             StaffUserRepository staff, ImageCleanupConfig config, CloudinaryUploader cloudinary,
                             ObjectMapper json, ApplicationEventPublisher events, ProductOptionsService productOptions) {
        this.productOptions = productOptions;
        this.jobs = jobs;
        this.items = items;
        this.writer = writer;
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.staff = staff;
        this.config = config;
        this.cloudinary = cloudinary;
        this.json = json;
        this.events = events;
    }

    // ---------------------------------------------------------------- preview / start

    /** What the request would make and what is wrong or unusual about it. Writes nothing. */
    @Transactional(readOnly = true)
    public Plan preview(VariantJobRequest r) {
        if (r == null || r.productId() == null) {
            throw new IllegalArgumentException("Say which product the variants are for.");
        }
        Product product = products.findById(r.productId()).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such product."));
        ProductVariant base = writer.base(product.getId(), r.baseVariantId())
                .orElseThrow(() -> new NotFoundException(r.baseVariantId() == null
                        ? "The product has no variant to make the new ones from. Save it with one first."
                        : "That variant is not on this product."));
        Set<String> existing = writer.existingCombos(product.getId());
        List<String> extraErrors = new ArrayList<>();
        List<String> extraWarnings = new ArrayList<>();
        String baseColor = ProductOptionsService.clean(base.getOptionColor());
        String baseSize = ProductOptionsService.clean(base.getOptionSize());
        if (r.baseOption() != null) {
            String wantColor = ProductOptionsService.clean(r.baseOption().color());
            String wantSize = ProductOptionsService.clean(r.baseOption().size());
            boolean hasOwn = baseColor != null || baseSize != null;
            if (hasOwn && !(Objects.equals(lower(baseColor), lower(wantColor)) && Objects.equals(lower(baseSize), lower(wantSize)))) {
                extraErrors.add("The product's own variant is already " + label(baseColor, baseSize)
                        + ". Change it in the product editor, not here.");
            } else if (!hasOwn) {
                if (wantColor != null && !listed(r.colors() == null ? List.of() : r.colors().stream().map(VariantJobRequest.ColorIn::name).toList(), wantColor)) {
                    extraErrors.add("The product's own colour \"" + wantColor + "\" must be one of the colours.");
                }
                if (wantSize != null && !listed(r.sizes() == null ? List.of() : r.sizes().stream().map(VariantJobRequest.SizeIn::name).toList(), wantSize)) {
                    extraErrors.add("The product's own size \"" + wantSize + "\" must be one of the sizes.");
                }
                baseColor = wantColor;
                baseSize = wantSize;
            }
        }
        if (baseColor != null || baseSize != null) {
            existing.add(VariantJobValidator.cellKey(listSpelling(r, baseColor, true), listSpelling(r, baseSize, false)));
        } else if ((r.colors() != null && !r.colors().isEmpty()) || (r.sizes() != null && !r.sizes().isEmpty())) {
            extraWarnings.add("The product's own variant has no colour or size, so it won't show in the colour and size "
                    + "picker. Say what it is under \"This product itself is\".");
        }
        VariantJobWriter.Source photo = writer.basePhoto(product.getId(), base.getId());
        Plan plan = VariantJobValidator.plan(r, new VariantJobValidator.Context(product.getName(), existing,
                photo != null, writer.productImageIds(product.getId()), "PIECE".equalsIgnoreCase(base.getUom())));
        plan.errors().addAll(extraErrors);
        plan.warnings().addAll(extraWarnings);
        return plan;
    }

    /** Queues the job; refused (with the plan) while the request has errors. */
    @Transactional
    public JobDto start(VariantJobRequest r, StaffUser by, Plan plan) {
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("Gemini isn't set up yet. Add the GEMINI_API_KEY in Settings, Integrations.");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Cloudinary isn't set up yet, so the photos can't be stored.");
        }
        if (r.isRedo()) {
            throw new UnprocessableException("Use Redo photos for variants that already exist.");
        }
        r.guides().check(cloudinary::isOwnUrl);
        VariantJob job = new VariantJob();
        job.setProductId(r.productId());
        job.setStatus(Status.QUEUED);
        job.setStage(Stage.CREATE);
        job.setApproveFirst(r.approveFirst());
        job.setCreatedBy(by == null ? null : by.getId());
        try {
            job.setRequest(json.writeValueAsString(r));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The request can't be saved: " + e.getMessage());
        }
        job.setSummary(String.join("\n", plan.summary()));
        List<VariantJobValidator.Row> toMake = plan.toMake();
        job.setVariantsTotal(toMake.size());
        job = jobs.saveAndFlush(job);
        int position = 0;
        List<VariantJobItem> rows = new ArrayList<>();
        for (VariantJobValidator.Row row : toMake) {
            VariantJobItem item = new VariantJobItem();
            item.setJobId(job.getId());
            item.setPosition(position++);
            item.setColor(row.color());
            item.setSize(row.size());
            item.setPrice(row.price());
            item.setOpeningStock(row.openingStock() != null && row.openingStock().signum() > 0 ? row.openingStock() : null);
            item.setUnitCost(row.unitCost());
            rows.add(item);
        }
        items.saveAll(rows);
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.VARIANT_JOBS));
        return dto(job, names(List.of(job)), productNames(List.of(job)));
    }

    /** "Redo photos": which existing variants, made from which variant's photo, following which instructions. */
    public record RedoRequest(UUID productId, UUID baseVariantId, List<UUID> variantIds,
                              VariantJobRequest.PhotoGuide photoGuide, List<VariantJobRequest.PhotoGroup> photoGroups,
                              List<VariantJobRequest.TrialPhoto> trialPhotos, String prompt) {}

    /**
     * Queues new photos for existing variants (owner, 2026-10-15: a whole job of gel polish photos came out painted
     * all over). A photo-only job: nothing is created and no description is written; each variant's AI-made photos are
     * replaced by the new one (photos staff uploaded stay). Each variant needs a colour, and the variant the photos are
     * made from is left as it is.
     */
    @Transactional
    public JobDto redo(RedoRequest r, StaffUser by) {
        if (r == null || r.productId() == null) {
            throw new UnprocessableException("Say which product's photos to redo.");
        }
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("Gemini isn't set up yet. Add the GEMINI_API_KEY in Settings, Integrations.");
        }
        if (!cloudinary.configured()) {
            throw new UnprocessableException("Cloudinary isn't set up yet, so the photos can't be stored.");
        }
        Product product = products.findById(r.productId()).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such product."));
        ProductVariant base = writer.base(product.getId(), r.baseVariantId())
                .orElseThrow(() -> new NotFoundException("Pick the variant whose photo the new ones are made from."));
        if (writer.basePhoto(product.getId(), base.getId()) == null) {
            throw new UnprocessableException("That variant has no photo to make the others from. Add one first.");
        }
        if (jobs.findByStatusInOrderByCreatedAtDesc(EnumSet.of(Status.QUEUED, Status.RUNNING, Status.PAUSED_QUOTA))
                .stream().anyMatch(j -> j.getProductId().equals(product.getId()))) {
            throw new ConflictException("A job for this product is still working. Wait for it to finish.");
        }
        Set<UUID> wanted = new LinkedHashSet<>(r.variantIds() == null ? List.of() : r.variantIds());
        wanted.remove(base.getId());
        if (wanted.isEmpty()) {
            throw new UnprocessableException("Tick the variants whose photos should be made again.");
        }
        Map<UUID, ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId())
                .stream().collect(Collectors.toMap(ProductVariant::getId, v -> v, (a, b) -> a, LinkedHashMap::new));
        List<ProductVariant> chosen = new ArrayList<>();
        for (ProductVariant v : live.values()) {
            if (!wanted.contains(v.getId())) continue;
            if (ProductOptionsService.clean(v.getOptionColor()) == null) {
                throw new UnprocessableException("\"" + v.displayLabel() + "\" has no colour, so there is nothing to "
                        + "make its photo from. Untick it.");
            }
            chosen.add(v);
        }
        if (chosen.size() != wanted.size()) {
            throw new NotFoundException("Some of the ticked variants aren't on this product any more. Reload and try again.");
        }

        // The job carries the product's colour and size lists (dots, size label text and photos), as a Generate does,
        // so the photo step works exactly the same.
        List<VariantJobRequest.ColorIn> colors = new ArrayList<>();
        List<VariantJobRequest.SizeIn> sizes = new ArrayList<>();
        for (ProductOptionsService.OptionDto o : productOptions.list(product.getId())) {
            if (o.kind() == com.nbh.backend.catalog.entity.ProductOptionValue.Kind.COLOR) {
                colors.add(new VariantJobRequest.ColorIn(o.value(), o.swatchHex(), o.colorFamily()));
            } else {
                sizes.add(new VariantJobRequest.SizeIn(o.value(), o.sizeLabelText(), o.sizePhotoImageId()));
            }
        }
        VariantJobRequest request = new VariantJobRequest(product.getId(), base.getId(), colors, sizes, null, null,
                null, null, null, false, r.prompt(), r.photoGuide(), r.photoGroups(), r.trialPhotos(),
                chosen.stream().map(ProductVariant::getId).toList());
        request.guides().check(cloudinary::isOwnUrl);

        VariantJob job = new VariantJob();
        job.setProductId(product.getId());
        job.setStatus(Status.QUEUED);
        job.setStage(Stage.PHOTOS);
        job.setApproveFirst(false);
        job.setCreatedBy(by == null ? null : by.getId());
        try {
            job.setRequest(json.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The request can't be saved: " + e.getMessage());
        }
        job.setSummary("Redo the photos of " + chosen.size() + " variant" + (chosen.size() == 1 ? "" : "s")
                + ", made from the photo of " + (base.getOptionColor() != null || base.getOptionSize() != null
                ? new com.nbh.backend.variantgen.VariantCombos.Combo(base.getOptionColor(), base.getOptionSize()).label()
                : base.getVariantName()) + ".");
        job.setVariantsTotal(chosen.size());
        job.setVariantsCreated(chosen.size());
        job = jobs.saveAndFlush(job);
        int position = 0;
        List<VariantJobItem> rows = new ArrayList<>();
        for (ProductVariant v : chosen) {
            VariantJobItem item = new VariantJobItem();
            item.setJobId(job.getId());
            item.setPosition(position++);
            item.setVariantId(v.getId());
            item.setColor(ProductOptionsService.clean(v.getOptionColor()));
            item.setSize(ProductOptionsService.clean(v.getOptionSize()));
            item.setPrice(v.getSellingPrice() == null ? BigDecimal.ZERO : v.getSellingPrice());
            item.setDescriptionStatus(StepStatus.SKIPPED);
            rows.add(item);
        }
        items.saveAll(rows);
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.VARIANT_JOBS));
        return dto(job, names(List.of(job)), productNames(List.of(job)));
    }

    // ---------------------------------------------------------------- reading

    /** Jobs still working (or paused) plus the latest finished ones, newest first. */
    @Transactional(readOnly = true)
    public List<JobDto> list(String which) {
        List<VariantJob> found = "active".equalsIgnoreCase(which)
                ? jobs.findByStatusInOrderByCreatedAtDesc(EnumSet.of(Status.QUEUED, Status.RUNNING, Status.PAUSED_QUOTA))
                : jobs.findTop50ByOrderByCreatedAtDesc();
        Map<UUID, String> names = names(found);
        Map<UUID, String> productNames = productNames(found);
        return found.stream().map(j -> dto(j, names, productNames)).toList();
    }

    @Transactional(readOnly = true)
    public JobDetailDto get(UUID jobId) {
        VariantJob job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("No such job."));
        List<VariantJobItem> its = items.findByJobIdOrderByPositionAsc(jobId);
        List<UUID> variantIds = its.stream().map(VariantJobItem::getVariantId).filter(Objects::nonNull).toList();
        Map<UUID, ProductVariant> vs = variants.findAllById(variantIds).stream()
                .collect(Collectors.toMap(ProductVariant::getId, v -> v));
        Map<UUID, String> photos = new HashMap<>();
        for (UUID id : variantIds) {
            images.findByVariantIdOrderBySortOrderAsc(id).stream().filter(ProductImage::isPrimary).findFirst()
                    .ifPresent(i -> photos.put(id, i.getUrl()));
        }
        // An item's "live" is null for a variant that is gone (removed since), else whether it is on sale.
        List<ItemDto> out = its.stream().map(i -> {
            ProductVariant v = i.getVariantId() == null ? null : vs.get(i.getVariantId());
            return new ItemDto(i.getId(), i.getPosition(), i.getVariantId(), i.getColor(), i.getSize(), i.label(),
                    i.getPrice(), v == null ? null : v.getSku(), v == null || v.isDeleted() ? null : v.isActive(),
                    i.getVariantId() == null ? null : photos.get(i.getVariantId()), v == null ? null : v.getSwatchHex(),
                    i.getDescriptionStatus(), i.getPhotoStatus(), i.getLastError());
        }).toList();
        return new JobDetailDto(dto(job, names(List.of(job)), productNames(List.of(job))), out);
    }

    // ---------------------------------------------------------------- actions

    /** An approve-first job: its hidden variants go on sale. */
    @Transactional
    public JobDetailDto publish(UUID jobId) {
        VariantJob job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("No such job."));
        if (job.getStatus() != Status.AWAITING_APPROVAL) {
            throw new ConflictException("Only a finished job waiting for approval can be published.");
        }
        for (VariantJobItem i : items.findByJobIdOrderByPositionAsc(jobId)) {
            if (i.getVariantId() == null) continue;
            variants.findById(i.getVariantId()).filter(v -> !v.isDeleted()).ifPresent(v -> {
                v.setActive(true);
                variants.save(v);
            });
        }
        writer.recount(job);
        job.setStatus(job.getPhotosFailed() + job.getDescriptionsFailed() > 0 ? Status.COMPLETED_WITH_ERRORS : Status.COMPLETED);
        jobs.save(job);
        // Variants going on sale changes what the product editor holds: move the version so an older form is refused.
        products.touch(job.getProductId(), Instant.now());
        writer.announce();
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        return get(jobId);
    }

    /** Puts the failed descriptions and photos back in the queue (and a failed job back to work). */
    @Transactional
    public JobDetailDto retry(UUID jobId) {
        VariantJob job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("No such job."));
        if (job.getStatus().active()) {
            throw new ConflictException("The job is still working. Wait for it to finish.");
        }
        if (job.getStatus() == Status.CANCELLED) {
            throw new ConflictException("A cancelled job can't be retried. Start a new one from the product.");
        }
        int reset = 0;
        int fixedByHand = 0;
        List<VariantJobItem> its = items.findByJobIdOrderByPositionAsc(jobId);
        // A failed photo that staff have since fixed themselves (a photo of their own on the variant, or the variant
        // removed) is not made again: the AI would be paid for a second photo that then goes on top of theirs. Not for
        // a "Redo photos" job, whose variants have photos by design.
        Set<UUID> handled = isRedo(job) ? Set.of() : variantsWithFailedPhotoFixedByHand(its);
        for (VariantJobItem i : its) {
            if (i.getDescriptionStatus() == StepStatus.FAILED) {
                i.setDescriptionStatus(StepStatus.PENDING);
                reset++;
            }
            if (i.getPhotoStatus() == StepStatus.FAILED) {
                if (i.getVariantId() != null && handled.contains(i.getVariantId())) {
                    i.setPhotoStatus(StepStatus.SKIPPED);
                    i.setLastError(null);
                    fixedByHand++;
                } else {
                    i.setPhotoStatus(StepStatus.PENDING);
                    reset++;
                }
            }
            if (i.getDescriptionStatus() == StepStatus.PENDING || i.getPhotoStatus() == StepStatus.PENDING) {
                i.setLastError(null);
            }
        }
        if (reset == 0 && fixedByHand > 0 && job.getStatus() != Status.FAILED) {
            // Everything that had failed was fixed by hand since: nothing to run, the job is simply finished.
            items.saveAll(its);
            writer.recount(job);
            if (job.getStatus() == Status.COMPLETED_WITH_ERRORS) {
                job.setStatus(Status.COMPLETED);
            }
            jobs.save(job);
            writer.announce();
            return get(jobId);
        }
        if (reset == 0 && job.getStatus() != Status.FAILED) {
            throw new ConflictException("Nothing in this job needs retrying.");
        }
        items.saveAll(its);
        if (job.getStage() != Stage.CREATE) {
            job.setStage(Stage.DESCRIPTIONS);
        }
        job.setStatus(Status.QUEUED);
        job.setLastError(null);
        job.setFinishedAt(null);
        job.setCancelRequested(false);
        jobs.save(job);
        writer.announce();
        return get(jobId);
    }

    /** The variants whose photo failed and that staff have since given a photo of their own, or removed. */
    private Set<UUID> variantsWithFailedPhotoFixedByHand(List<VariantJobItem> its) {
        Set<UUID> failed = its.stream()
                .filter(i -> i.getPhotoStatus() == StepStatus.FAILED && i.getVariantId() != null)
                .map(VariantJobItem::getVariantId)
                .collect(Collectors.toSet());
        return failed.isEmpty() ? Set.of() : new HashSet<>(variants.findIdsRemovedOrWithOwnPhoto(failed));
    }

    /**
     * Staff have looked at what a finished job could not do and want it off the list without retrying (no AI quota
     * spent). The failed photos and descriptions are left as they are and marked as set aside; a job that stopped
     * (FAILED) is ended. Variants already made stay.
     */
    @Transactional
    public JobDetailDto dismiss(UUID jobId) {
        VariantJob job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("No such job."));
        if (job.getStatus().active()) {
            throw new ConflictException("The job is still working. Wait for it to finish.");
        }
        if (job.getStatus() == Status.AWAITING_APPROVAL) {
            throw new ConflictException("This job is waiting for your approval. Publish it first.");
        }
        List<VariantJobItem> its = items.findByJobIdOrderByPositionAsc(jobId);
        for (VariantJobItem i : its) {
            if (i.getPhotoStatus() == StepStatus.FAILED) {
                i.setPhotoStatus(StepStatus.SKIPPED);
            }
            if (i.getDescriptionStatus() == StepStatus.FAILED) {
                i.setDescriptionStatus(StepStatus.SKIPPED);
            }
        }
        items.saveAll(its);
        writer.recount(job);
        if (job.getStatus() == Status.FAILED) {
            job.setStatus(Status.CANCELLED);
            if (job.getFinishedAt() == null) {
                job.setFinishedAt(Instant.now());
            }
        } else if (job.getStatus() == Status.COMPLETED_WITH_ERRORS) {
            job.setStatus(Status.COMPLETED);
        }
        jobs.save(job);
        writer.announce();
        return get(jobId);
    }

    /** Stops a job: at once if it is waiting, between two items if it is working. Variants already made stay. */
    @Transactional
    public JobDetailDto cancel(UUID jobId) {
        VariantJob job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("No such job."));
        if (!job.getStatus().active()) {
            throw new ConflictException("The job has already finished.");
        }
        if (job.getStatus() == Status.RUNNING) {
            job.setCancelRequested(true);
        } else {
            job.setStatus(Status.CANCELLED);
            job.setFinishedAt(Instant.now());
            job.setLockedBy(null);
        }
        jobs.save(job);
        writer.announce();
        return get(jobId);
    }

    // ---------------------------------------------------------------- helpers

    private JobDto dto(VariantJob j, Map<UUID, String> names, Map<UUID, String> productNames) {
        List<String> summary = j.getSummary() == null || j.getSummary().isBlank() ? List.of()
                : List.of(j.getSummary().split("\n"));
        return new JobDto(j.getId(), j.getProductId(), productNames.get(j.getProductId()), j.getStatus(), j.getStage(),
                j.isApproveFirst(), summary, j.getVariantsTotal(), j.getVariantsCreated(), j.getDescriptionsDone(),
                j.getDescriptionsFailed(), j.getPhotosDone(), j.getPhotosFailed(), j.getLastError(), j.getNextAttemptAt(),
                j.getCreatedBy(), j.getCreatedBy() == null ? null : names.get(j.getCreatedBy()), j.getCreatedAt(),
                j.getStartedAt(), j.getFinishedAt(), isRedo(j));
    }

    /** A "Redo photos" job (its saved request names the variants to redo); read without parsing the whole request. */
    static boolean isRedo(VariantJob j) {
        return j.getRequest() != null && j.getRequest().contains("\"redoVariantIds\":[\"");
    }

    private Map<UUID, String> names(List<VariantJob> js) {
        Set<UUID> ids = js.stream().map(VariantJob::getCreatedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> out = new HashMap<>();
        staff.findAllById(ids).forEach(s -> out.put(s.getId(),
                ((s.getFirstName() == null ? "" : s.getFirstName()) + " " + (s.getLastName() == null ? "" : s.getLastName())).trim()));
        return out;
    }

    private Map<UUID, String> productNames(List<VariantJob> js) {
        Set<UUID> ids = js.stream().map(VariantJob::getProductId).collect(Collectors.toSet());
        Map<UUID, String> out = new HashMap<>();
        products.findAllById(ids).forEach(p -> out.put(p.getId(), p.getName()));
        return out;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static boolean listed(List<String> names, String value) {
        return names.stream().anyMatch(n -> VariantJobWriter.sameValue(n, value));
    }

    /** How the wizard's list spells a value, so the base's combination matches the rows' keys. */
    private static String listSpelling(VariantJobRequest r, String value, boolean color) {
        if (value == null) return null;
        List<String> names = color
                ? (r.colors() == null ? List.<String>of() : r.colors().stream().map(VariantJobRequest.ColorIn::name).toList())
                : (r.sizes() == null ? List.<String>of() : r.sizes().stream().map(VariantJobRequest.SizeIn::name).toList());
        return names.stream().filter(n -> VariantJobWriter.sameValue(n, value)).map(ProductOptionsService::clean)
                .findFirst().orElse(value);
    }

    private static String label(String color, String size) {
        return new com.nbh.backend.variantgen.VariantCombos.Combo(color, size).label();
    }
}
