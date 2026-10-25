package com.nbh.backend.variantjobs;

import com.nbh.backend.catalog.service.ImageAssetCleaner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ColorFamily;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductOptionValue;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductOptionValueRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.ProductOptionsService;
import com.nbh.backend.catalog.service.ProductService;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.service.VariantStockService;
import com.nbh.backend.variantgen.VariantCombos;
import com.nbh.backend.variantjobs.VariantJob.Stage;
import com.nbh.backend.variantjobs.VariantJob.Status;
import com.nbh.backend.variantjobs.VariantJobItem.StepStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** The database half of a variant job: each method is its own short transaction, kept apart from Gemini/Cloudinary calls. */
@Service
public class VariantJobWriter {

    /** A photo the job can start from: the base variant's own main photo, or one of a size's photos. */
    public record Source(UUID imageId, String url) {}

    private final VariantJobRepository jobs;
    private final VariantJobItemRepository items;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final ProductOptionValueRepository optionRows;
    private final ProductOptionsService productOptions;
    private final ProductService productService;
    private final VariantStockService stock;
    private final StaffUserRepository staff;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final ImageAssetCleaner assetCleaner;

    /** A range's configuration rules for the variants a job makes (V68); optional so hand-built writers still work. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.nbh.backend.catalog.service.RangeService ranges;

    public VariantJobWriter(VariantJobRepository jobs, VariantJobItemRepository items, ProductRepository products,
                            ProductVariantRepository variants, ProductImageRepository images,
                            ProductOptionValueRepository optionRows, ProductOptionsService productOptions,
                            ProductService productService, VariantStockService stock, StaffUserRepository staff,
                            ObjectMapper json, ApplicationEventPublisher events, ImageAssetCleaner assetCleaner) {
        this.assetCleaner = assetCleaner;
        this.jobs = jobs;
        this.items = items;
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.optionRows = optionRows;
        this.productOptions = productOptions;
        this.productService = productService;
        this.stock = stock;
        this.staff = staff;
        this.json = json;
        this.events = events;
    }

    // ---------------------------------------------------------------- reading

    public VariantJobRequest request(VariantJob job) {
        try {
            return json.readValue(job.getRequest(), VariantJobRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The job's saved request can't be read: " + e.getMessage(), e);
        }
    }

    /** The base variant: the one asked for, else the product's first live variant that is not a package. */
    @Transactional(readOnly = true)
    public Optional<ProductVariant> base(UUID productId, UUID baseVariantId) {
        List<ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId);
        return baseVariantId == null ? live.stream().filter(v -> !v.isPackage()).findFirst()
                : live.stream().filter(v -> v.getId().equals(baseVariantId)).findFirst();
    }

    /** The base variant's main photo (its own, else a photo not given to any option), or null. */
    @Transactional(readOnly = true)
    public Source basePhoto(UUID productId, UUID baseVariantId) {
        List<ProductImage> own = images.findByVariantIdOrderBySortOrderAsc(baseVariantId);
        ProductImage pick = own.stream().filter(ProductImage::isPrimary).findFirst().or(() -> own.stream().findFirst())
                .orElse(null);
        if (pick == null) {
            List<ProductImage> loose = images.findByProductIdOrderBySortOrderAsc(productId).stream()
                    .filter(i -> i.getVariant() == null).toList();
            pick = loose.stream().filter(ProductImage::isPrimary).findFirst().or(() -> loose.stream().findFirst()).orElse(null);
        }
        return pick == null ? null : new Source(pick.getId(), pick.getUrl());
    }

    @Transactional(readOnly = true)
    public Set<UUID> productImageIds(UUID productId) {
        Set<UUID> ids = new HashSet<>();
        images.findByProductIdOrderBySortOrderAsc(productId).forEach(i -> ids.add(i.getId()));
        return ids;
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> imageUrls(Collection<UUID> imageIds) {
        Map<UUID, String> out = new HashMap<>();
        images.findAllById(imageIds).forEach(i -> out.put(i.getId(), i.getUrl()));
        return out;
    }

    /** The combinations the product's live variants already are, keyed like {@link VariantJobValidator#cellKey}. */
    @Transactional(readOnly = true)
    public Set<String> existingCombos(UUID productId) {
        Set<String> out = new HashSet<>();
        for (ProductVariant v : variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId)) {
            String c = ProductOptionsService.clean(v.getOptionColor());
            String s = ProductOptionsService.clean(v.getOptionSize());
            if (c != null || s != null) {
                out.add(VariantJobValidator.cellKey(c, s));
                // The same colour as the wizard now sends it back (tidied): a re-run must not make it again.
                out.add(VariantJobValidator.cellKey(c == null ? null : VariantCombos.tidyColour(c), s));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- claiming

    /** Takes the next job a worker may run (see {@link VariantJobRepository#claimable}), marking it RUNNING. */
    @Transactional
    public Optional<UUID> claim(String workerId, Instant staleBefore) {
        Instant now = Instant.now();
        return jobs.claimable(now, staleBefore).map(job -> {
            job.setStatus(Status.RUNNING);
            job.setLockedBy(workerId);
            job.setHeartbeatAt(now);
            job.setNextAttemptAt(null);
            if (job.getStartedAt() == null) {
                job.setStartedAt(now);
            }
            jobs.save(job);
            announce();
            return job.getId();
        });
    }

    @Transactional
    public void heartbeat(UUID jobId) {
        jobs.findById(jobId).ifPresent(j -> {
            j.setHeartbeatAt(Instant.now());
            jobs.save(j);
        });
    }

    @Transactional(readOnly = true)
    public boolean cancelRequested(UUID jobId) {
        return jobs.findById(jobId).map(VariantJob::isCancelRequested).orElse(true);
    }

    // ---------------------------------------------------------------- step 1: create

    /**
     * Makes every variant the job still has to make, in one transaction (all or nothing, so a restart simply runs it
     * again): SKU, name, colour/size/dot/family, price, the base's unit/VAT/weight, hidden when approve-first; opening
     * stock posted as a receipt; the product's colour and size list updated with the dots, families, label text and
     * size photos staff gave. A combination the product gained since the preview is skipped, not doubled. The product
     * row is locked first, so two jobs for one product cannot both make the same combination.
     */
    @Transactional
    public void createAll(UUID jobId) {
        VariantJob job = jobs.findById(jobId).orElseThrow();
        VariantJobRequest r = request(job);
        jobs.lockProduct(job.getProductId());
        Product product = products.findById(job.getProductId()).orElseThrow();
        ProductVariant base = base(product.getId(), r.baseVariantId())
                .orElseThrow(() -> new IllegalStateException("The product has no variant left to make the new ones from."));
        StaffUser by = job.getCreatedBy() == null ? null : staff.findById(job.getCreatedBy()).orElse(null);

        if (r.baseOption() != null && base.getOptionColor() == null && base.getOptionSize() == null) {
            // Spelled as the wizard's lists spell them ("white" typed for the base is "White"), since the base, being
            // first, would otherwise set the spelling every variant of that colour gets.
            base.setOptionColor(spelled(nn(r.colors()).stream().map(VariantJobRequest.ColorIn::name).toList(),
                    r.baseOption().color()));
            base.setOptionSize(spelled(nn(r.sizes()).stream().map(VariantJobRequest.SizeIn::name).toList(),
                    r.baseOption().size()));
            base.setSwatchHex(base.getOptionColor() == null ? null : hexOf(r, base.getOptionColor()));
            base.setColorFamily(base.getOptionColor() == null ? null : familyOf(r, base.getOptionColor()));
            // A placeholder name ("Default", blank) becomes the generated one, like its new siblings'; a real name stays.
            if ((base.getOptionColor() != null || base.getOptionSize() != null)
                    && com.nbh.backend.catalog.dto.CatalogVariantCardDto.isMeaninglessName(base.getVariantName())) {
                base.setVariantName(VariantCombos.variantName(product.getName(),
                        new VariantCombos.Combo(base.getOptionColor(), base.getOptionSize()).label()));
            }
            variants.save(base);
        }
        Set<String> existing = existingCombos(product.getId());
        if (base.getOptionColor() != null || base.getOptionSize() != null) {
            existing.add(VariantJobValidator.cellKey(base.getOptionColor(), base.getOptionSize()));
        }
        int order = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId()).stream()
                .mapToInt(ProductVariant::getSortOrder).max().orElse(-1) + 1;
        int created = 0;
        List<VariantJobItem> jobItems = items.findByJobIdOrderByPositionAsc(jobId);
        for (VariantJobItem item : jobItems) {
            if (item.getVariantId() != null) {
                created++;
                continue;
            }
            if (existing.contains(VariantJobValidator.cellKey(item.getColor(), item.getSize()))) {
                item.setDescriptionStatus(StepStatus.SKIPPED);
                item.setPhotoStatus(StepStatus.SKIPPED);
                item.setLastError("The product already had this one when the job started, so it was not made again.");
                continue;
            }
            ProductVariant v = new ProductVariant();
            v.setProduct(product);
            v.setSku(productService.nextSku(product));
            String label = item.label();
            v.setVariantName(VariantCombos.variantName(product.getName(), label));
            v.setOptionLabel(label);
            v.setOptionColor(item.getColor());
            v.setOptionSize(item.getSize());
            v.setSwatchHex(item.getColor() == null ? null : hexOf(r, item.getColor()));
            v.setColorFamily(item.getColor() == null ? null : familyOf(r, item.getColor()));
            v.setDescription(base.getDescription());
            v.setSellingPrice(item.getPrice());
            // An unknown cost stays unknown for a NEW size: the base's cost belongs to the base's own size (a 250 ml
            // does not cost what the 50 ml does). A colour of the base's size does cost what the base costs.
            v.setStandardCost(item.getUnitCost() != null ? item.getUnitCost()
                    : sameSize(item.getSize(), base.getOptionSize()) ? base.getStandardCost() : null);
            v.setUom(base.getUom());
            v.setWeightGrams(base.getWeightGrams());
            v.setVolumeMl(base.getVolumeMl());
            v.setVatRate(base.getVatRate());
            v.setVatExempt(base.isVatExempt());
            v.setActive(!job.isApproveFirst());
            v.setSortOrder(order++);
            v.setGeneratedFromVariantId(base.getId());
            v = variants.saveAndFlush(v);
            item.setVariantId(v.getId());
            existing.add(VariantJobValidator.cellKey(item.getColor(), item.getSize()));
            created++;
            if (item.getOpeningStock() != null && item.getOpeningStock().signum() > 0) {
                if (by == null) {
                    item.setLastError("Opening stock was not added: the staff member who started the job no longer exists.");
                } else {
                    stock.addOnHand(product.getId(), v.getId(), item.getOpeningStock(), item.getUnitCost(),
                            "Opening stock (variant job)", true, by);
                }
            }
        }
        items.saveAll(jobItems);
        productOptions.sync(product.getId());
        applyOptionDetails(product.getId(), r);
        if (ranges != null) {
            ranges.enforce(product.getId(), true); // a confirmed range: the new variants take its common name and rules
        }

        products.touch(product.getId(), Instant.now());
        job.setVariantsCreated(created);
        job.setStage(Stage.DESCRIPTIONS);
        job.setHeartbeatAt(Instant.now());
        jobs.save(job);
        announce();
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
    }

    /** The dots and families staff confirmed reach the colour list; label text and size photos reach the size list. */
    private void applyOptionDetails(UUID productId, VariantJobRequest r) {
        for (ProductOptionValue row : optionRows.findByProductIdOrderByKindAscPositionAsc(productId)) {
            if (row.getKind() == ProductOptionValue.Kind.COLOR) {
                for (VariantJobRequest.ColorIn c : nn(r.colors())) {
                    if (sameValue(c.name(), row.getValue())) {
                        String hex = VariantCombos.normaliseHex(c.hex());
                        if (hex != null) row.setSwatchHex(hex);
                        ColorFamily f = c.family() == null || c.family().isBlank() ? null : ColorFamily.parse(c.family());
                        if (f != null) row.setColorFamily(f.name());
                    }
                }
            } else {
                for (VariantJobRequest.SizeIn s : nn(r.sizes())) {
                    if (sameValue(s.name(), row.getValue())) {
                        if (s.labelText() != null && !s.labelText().isBlank()) {
                            row.setSizeLabelText(s.labelText().replaceAll("\\s+", " ").trim());
                        }
                        if (s.photoImageId() != null) row.setSizePhotoImageId(s.photoImageId());
                    }
                }
            }
        }
        optionRows.flush();
    }

    // ---------------------------------------------------------------- steps 2 and 3

    @Transactional
    public void saveDescriptions(UUID jobId, Map<UUID, String> byItem) {
        VariantJob job = jobs.findById(jobId).orElseThrow();
        Map<UUID, VariantJobItem> all = new HashMap<>();
        items.findByJobIdOrderByPositionAsc(jobId).forEach(i -> all.put(i.getId(), i));
        for (Map.Entry<UUID, String> e : byItem.entrySet()) {
            VariantJobItem item = all.get(e.getKey());
            if (item == null || item.getVariantId() == null) continue;
            if (e.getValue() == null) {
                item.setDescriptionStatus(StepStatus.FAILED);
                item.setLastError("The description could not be written.");
            } else {
                variants.findById(item.getVariantId()).ifPresent(v -> {
                    v.setDescription(e.getValue());
                    variants.save(v);
                });
                item.setDescriptionStatus(StepStatus.DONE);
            }
        }
        items.saveAll(all.values());
        recount(job);
        job.setHeartbeatAt(Instant.now());
        jobs.save(job);
        // The variants' descriptions are part of what the product editor holds. Moving the product's version makes an
        // editor opened before this refuse its save (or reload) instead of putting its older descriptions back.
        products.touch(job.getProductId(), Instant.now());
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        announce();
    }

    @Transactional
    public void setStage(UUID jobId, Stage stage) {
        jobs.findById(jobId).ifPresent(j -> {
            j.setStage(stage);
            jobs.save(j);
        });
    }

    /**
     * Puts a finished photo on the variant as its main photo. {@code url} may be a file another photo row already uses
     * (the sizes of one colour share their colour's photo); a file is only deleted once no photo row uses it.
     */
    @Transactional
    public void attachPhoto(UUID jobId, UUID itemId, String url, boolean madeByAi) {
        attachPhoto(jobId, itemId, url, madeByAi, false);
    }

    /**
     * {@code replaceAiPhotos}: a "Redo photos" job. The variant's AI-made photos (the wrong ones being redone) are
     * removed, and their files once nothing else shows them; photos staff uploaded themselves stay, behind the new one.
     */
    @Transactional
    public void attachPhoto(UUID jobId, UUID itemId, String url, boolean madeByAi, boolean replaceAiPhotos) {
        attachPhoto(jobId, itemId, url, madeByAi, replaceAiPhotos, null);
    }

    /**
     * The product's own photos at {@code url} (the one a job's photos were made from) get {@code focus}: where the part
     * staff marked sits, so the shop's cards crop around it there too (V66).
     */
    @Transactional
    public void focusPhoto(UUID productId, String url, String focus) {
        if (url == null || focus == null) return;
        List<ProductImage> found = images.findByProductIdInOrderBySortOrderAsc(List.of(productId)).stream()
                .filter(i -> url.equals(i.getUrl()) && !focus.equals(i.getFocus()))
                .toList();
        found.forEach(i -> i.setFocus(focus));
        images.saveAll(found);
    }

    /** {@code focus}: where the marked part sits on the photo, "x% y%", or null for the middle (V66). */
    @Transactional
    public void attachPhoto(UUID jobId, UUID itemId, String url, boolean madeByAi, boolean replaceAiPhotos,
                            String focus) {
        VariantJobItem item = items.findById(itemId).orElseThrow();
        if (item.getPhotoStatus() != StepStatus.PENDING) {
            // Already done (or failed and handed back) by another run of this job: a worker that was thought dead and
            // came back must not add a second main photo or spend the item again.
            return;
        }
        ProductVariant v = variants.findById(item.getVariantId()).orElse(null);
        if (v != null && !v.isDeleted()) {
            List<ProductImage> own = new ArrayList<>(images.findByVariantIdOrderBySortOrderAsc(v.getId()));
            if (replaceAiPhotos) {
                List<ProductImage> wrong = own.stream()
                        .filter(e -> e.getSource() == ProductImage.ImageSource.AI_CLEANED && !url.equals(e.getUrl()))
                        .toList();
                if (!wrong.isEmpty()) {
                    images.deleteAll(wrong);
                    images.flush();
                    own.removeAll(wrong);
                    assetCleaner.releaseAfterCommit(wrong.stream().map(ProductImage::getUrl).toList());
                }
            }
            for (ProductImage e : own) {
                e.setSortOrder(e.getSortOrder() + 1);
                e.setPrimary(false);
            }
            images.saveAll(own);
            ProductImage image = new ProductImage();
            image.setProduct(v.getProduct());
            image.setVariant(v);
            image.setUrl(url);
            image.setAltText(v.displayLabel());
            image.setSortOrder(0);
            image.setPrimary(true);
            image.setSource(madeByAi ? ProductImage.ImageSource.AI_CLEANED : ProductImage.ImageSource.UPLOAD);
            image.setFocus(focus);
            images.save(image);
        }
        item.setPhotoStatus(StepStatus.DONE);
        item.setPhotoAttempts(item.getPhotoAttempts() + 1);
        item.setLastError(null);
        items.save(item);
        VariantJob job = jobs.findById(jobId).orElseThrow();
        recount(job);
        job.setHeartbeatAt(Instant.now());
        jobs.save(job);
        announce();
    }

    @Transactional
    public void photoFailed(UUID jobId, UUID itemId, String error) {
        VariantJobItem item = items.findById(itemId).orElseThrow();
        if (item.getPhotoStatus() == StepStatus.DONE) {
            return; // another run of this job already made it; a late failure report must not undo that
        }
        item.setPhotoStatus(StepStatus.FAILED);
        item.setPhotoAttempts(item.getPhotoAttempts() + 1);
        item.setLastError(trim(error));
        items.save(item);
        VariantJob job = jobs.findById(jobId).orElseThrow();
        recount(job);
        job.setHeartbeatAt(Instant.now());
        jobs.save(job);
        announce();
    }

    /** Of these variants, the ones that were removed since or that staff have given a photo of their own. */
    @Transactional(readOnly = true)
    public Set<UUID> removedOrWithOwnPhoto(Collection<UUID> variantIds) {
        return variantIds.isEmpty() ? Set.of() : new HashSet<>(variants.findIdsRemovedOrWithOwnPhoto(variantIds));
    }

    /**
     * The item's photo is not made: staff gave the variant one of their own while it waited (an AI quota pause can last
     * a quarter of an hour), or the variant is gone. The AI is not paid for a second photo that would go on top of
     * theirs.
     */
    @Transactional
    public void photoSkipped(UUID jobId, UUID itemId) {
        VariantJobItem item = items.findById(itemId).orElseThrow();
        if (item.getPhotoStatus() != StepStatus.PENDING) {
            return;
        }
        item.setPhotoStatus(StepStatus.SKIPPED);
        item.setLastError(null);
        items.save(item);
        VariantJob job = jobs.findById(jobId).orElseThrow();
        recount(job);
        job.setHeartbeatAt(Instant.now());
        jobs.save(job);
        announce();
    }

    // ---------------------------------------------------------------- ends

    /** The quota ran out: the job waits and is taken again by the poller once {@code until} has passed. */
    @Transactional
    public void pause(UUID jobId, Instant until, String why) {
        jobs.findById(jobId).ifPresent(j -> {
            j.setStatus(Status.PAUSED_QUOTA);
            j.setNextAttemptAt(until);
            j.setLockedBy(null);
            j.setLastError(trim(why));
            jobs.save(j);
            announce();
        });
    }

    @Transactional
    public void finish(UUID jobId) {
        VariantJob job = jobs.findById(jobId).orElseThrow();
        recount(job);
        job.setStage(Stage.DONE);
        job.setLockedBy(null);
        job.setFinishedAt(Instant.now());
        if (job.isCancelRequested()) {
            job.setStatus(Status.CANCELLED);
        } else if (job.isApproveFirst() && hasHidden(job)) {
            job.setStatus(Status.AWAITING_APPROVAL);
        } else {
            job.setStatus(job.getPhotosFailed() + job.getDescriptionsFailed() > 0
                    ? Status.COMPLETED_WITH_ERRORS : Status.COMPLETED);
        }
        if (job.getStatus() != Status.CANCELLED) {
            job.setLastError(null);
        }
        jobs.save(job);
        announce();
    }

    @Transactional
    public void fail(UUID jobId, String why) {
        jobs.findById(jobId).ifPresent(j -> {
            j.setStatus(Status.FAILED);
            j.setLockedBy(null);
            j.setFinishedAt(Instant.now());
            j.setLastError(trim(why));
            jobs.save(j);
            announce();
        });
    }

    private boolean hasHidden(VariantJob job) {
        return items.findByJobIdOrderByPositionAsc(job.getId()).stream()
                .map(VariantJobItem::getVariantId).filter(Objects::nonNull)
                .map(variants::findById).flatMap(Optional::stream)
                .anyMatch(v -> !v.isDeleted() && !v.isActive());
    }

    /** The job's counters from its items, so they are right however often a step was retried. */
    void recount(VariantJob job) {
        int dd = 0, df = 0, pd = 0, pf = 0, created = 0;
        for (VariantJobItem i : items.findByJobIdOrderByPositionAsc(job.getId())) {
            if (i.getVariantId() != null) created++;
            if (i.getDescriptionStatus() == StepStatus.DONE) dd++;
            if (i.getDescriptionStatus() == StepStatus.FAILED) df++;
            if (i.getPhotoStatus() == StepStatus.DONE) pd++;
            if (i.getPhotoStatus() == StepStatus.FAILED) pf++;
        }
        job.setVariantsCreated(created);
        job.setDescriptionsDone(dd);
        job.setDescriptionsFailed(df);
        job.setPhotosDone(pd);
        job.setPhotosFailed(pf);
    }

    void announce() {
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.VARIANT_JOBS));
    }

    // ---------------------------------------------------------------- helpers

    static String hexOf(VariantJobRequest r, String color) {
        for (VariantJobRequest.ColorIn c : nn(r.colors())) {
            if (sameValue(c.name(), color)) return VariantCombos.normaliseHex(c.hex());
        }
        return null;
    }

    static String familyOf(VariantJobRequest r, String color) {
        for (VariantJobRequest.ColorIn c : nn(r.colors())) {
            if (sameValue(c.name(), color) && c.family() != null && !c.family().isBlank()) {
                return ColorFamily.parse(c.family()).name();
            }
        }
        return null;
    }

    /** The list's spelling of {@code value}, else {@code value} cleaned; null when blank. */
    static String spelled(List<String> list, String value) {
        String v = ProductOptionsService.clean(value);
        if (v == null) return null;
        return list.stream().map(ProductOptionsService::clean).filter(n -> n != null && n.equalsIgnoreCase(v))
                .findFirst().orElse(v);
    }

    static boolean sameValue(String a, String b) {
        String x = ProductOptionsService.clean(a);
        String y = ProductOptionsService.clean(b);
        return x != null && y != null && x.equalsIgnoreCase(y);
    }

    /** Same size, ignoring case and spacing; two missing sizes count as the same (a colour-only job). */
    static boolean sameSize(String a, String b) {
        String x = ProductOptionsService.clean(a);
        String y = ProductOptionsService.clean(b);
        return x == null ? y == null : y != null && x.equalsIgnoreCase(y);
    }

    static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list;
    }

    static String trim(String s) {
        if (s == null) return null;
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
