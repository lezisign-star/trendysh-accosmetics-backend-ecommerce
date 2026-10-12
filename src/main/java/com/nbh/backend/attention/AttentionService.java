package com.nbh.backend.attention;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.service.StockAvailabilityService;
import com.nbh.backend.variantjobs.VariantJob;
import com.nbh.backend.variantjobs.VariantJob.Status;
import com.nbh.backend.variantjobs.VariantJobItem;
import com.nbh.backend.variantjobs.VariantJobItem.StepStatus;
import com.nbh.backend.variantjobs.VariantJobItemRepository;
import com.nbh.backend.variantjobs.VariantJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * "Needs attention" on the admin dashboard (VARIANTS_PLAN.md 3.5): only what a person has to do, each with the button
 * that does it. Worked out from the jobs and variants as they are now, never stored, so an item disappears the moment
 * the thing it is about is fixed. The header's count is the CRITICAL and ACTION items; INFO items are shown but not
 * counted.
 */
@Service
public class AttentionService {

    public enum Severity { CRITICAL, ACTION, INFO }

    /** What a button does. The screen knows where each kind leads; ids say what it is about. */
    public enum ActionKind { RETRY_JOB, OPEN_JOB, PUBLISH_JOB, UPLOAD_PHOTOS, ADD_STOCK }

    public record Action(String label, ActionKind kind) {}

    public record Item(String key, Severity severity, String title, String detail, UUID productId, String productName,
                       UUID jobId, List<UUID> variantIds, List<Action> actions, Instant since) {}

    public record Attention(int count, List<Item> items) {}

    /** Finished jobs older than this no longer raise items (except variants that still have no stock at all). */
    private static final Duration RECENT = Duration.ofDays(30);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Africa/Nairobi"));

    private final VariantJobRepository jobs;
    private final VariantJobItemRepository items;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final StockAvailabilityService availability;
    private final Clock clock;

    public AttentionService(VariantJobRepository jobs, VariantJobItemRepository items, ProductRepository products,
                            ProductVariantRepository variants, StockAvailabilityService availability, Clock clock) {
        this.jobs = jobs;
        this.items = items;
        this.products = products;
        this.variants = variants;
        this.availability = availability;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Attention list() {
        Instant cutoff = Instant.now(clock).minus(RECENT);
        List<VariantJob> recent = jobs.findTop50ByOrderByCreatedAtDesc().stream()
                .filter(j -> j.getStatus() != Status.CANCELLED)
                .filter(j -> j.getStatus().active() || j.getFinishedAt() == null || j.getFinishedAt().isAfter(cutoff))
                .toList();
        Map<UUID, String> productNames = new HashMap<>();
        products.findAllById(recent.stream().map(VariantJob::getProductId).collect(Collectors.toSet()))
                .stream().filter(p -> !p.isDeleted()).forEach(p -> productNames.put(p.getId(), p.getName()));
        Map<UUID, List<VariantJobItem>> itemsByJob = items.findByJobIdInOrderByPositionAsc(
                recent.stream().map(VariantJob::getId).toList()).stream()
                .collect(Collectors.groupingBy(VariantJobItem::getJobId, LinkedHashMap::new, Collectors.toList()));

        List<Item> out = new ArrayList<>();
        List<UUID> madeVariantIds = new ArrayList<>();
        for (VariantJob job : recent) {
            String product = productNames.get(job.getProductId());
            if (product == null) continue; // the product was removed
            List<VariantJobItem> its = itemsByJob.getOrDefault(job.getId(), List.of());
            boolean finished = !job.getStatus().active();

            if (job.getStatus() == Status.FAILED) {
                out.add(new Item("job-failed-" + job.getId(), Severity.CRITICAL, product + ": generation stopped",
                        job.getLastError(), job.getProductId(), product, job.getId(), List.of(),
                        List.of(new Action("See what happened", ActionKind.OPEN_JOB), new Action("Retry", ActionKind.RETRY_JOB)),
                        job.getFinishedAt()));
            }
            if (job.getStatus() == Status.PAUSED_QUOTA) {
                String when = job.getNextAttemptAt() == null ? "soon" : "around " + TIME.format(job.getNextAttemptAt());
                out.add(new Item("job-paused-" + job.getId(), Severity.INFO, "AI quota reached: " + product + " carries on " + when,
                        "Nothing to do: the job resumes by itself.", job.getProductId(), product, job.getId(), List.of(),
                        List.of(), job.getUpdatedAt()));
            }
            if (job.getStatus() == Status.AWAITING_APPROVAL) {
                int hidden = (int) its.stream().filter(i -> i.getVariantId() != null).count();
                out.add(new Item("job-approve-" + job.getId(), Severity.ACTION,
                        product + ": " + hidden + " variant" + (hidden == 1 ? "" : "s") + " waiting for your approval",
                        "Customers can't see them until you publish.", job.getProductId(), product, job.getId(), List.of(),
                        List.of(new Action("Review & publish", ActionKind.PUBLISH_JOB)), job.getFinishedAt()));
            }
            if (finished) {
                List<VariantJobItem> noPhoto = its.stream().filter(i -> i.getPhotoStatus() == StepStatus.FAILED).toList();
                if (!noPhoto.isEmpty()) {
                    out.add(new Item("job-photos-" + job.getId(), Severity.ACTION,
                            noPhoto.size() + " photo" + (noPhoto.size() == 1 ? "" : "s") + " failed for " + product,
                            "Without one: " + labels(noPhoto) + ".", job.getProductId(), product, job.getId(),
                            ids(noPhoto), List.of(new Action("Retry photos", ActionKind.RETRY_JOB),
                            new Action("Upload my own", ActionKind.UPLOAD_PHOTOS)), job.getFinishedAt()));
                }
                List<VariantJobItem> noText = its.stream().filter(i -> i.getDescriptionStatus() == StepStatus.FAILED).toList();
                if (!noText.isEmpty()) {
                    out.add(new Item("job-descriptions-" + job.getId(), Severity.ACTION,
                            product + ": " + noText.size() + " description" + (noText.size() == 1 ? "" : "s") + " not written",
                            "They show the product's own description until then.", job.getProductId(), product,
                            job.getId(), ids(noText), List.of(new Action("Retry", ActionKind.RETRY_JOB)), job.getFinishedAt()));
                }
                if (job.getStatus() == Status.COMPLETED || job.getStatus() == Status.COMPLETED_WITH_ERRORS) {
                    its.stream().map(VariantJobItem::getVariantId).filter(Objects::nonNull).forEach(madeVariantIds::add);
                }
            }
        }
        out.addAll(noStock(madeVariantIds, recent, itemsByJob, productNames));

        out.sort(Comparator.comparing(Item::severity)
                .thenComparing(Item::since, Comparator.nullsLast(Comparator.reverseOrder())));
        int count = (int) out.stream().filter(i -> i.severity() != Severity.INFO).count();
        return new Attention(count, out);
    }

    /** Live variants a job made that have never had any stock: customers see them as sold out. */
    private List<Item> noStock(List<UUID> variantIds, List<VariantJob> jobsList, Map<UUID, List<VariantJobItem>> itemsByJob,
                               Map<UUID, String> productNames) {
        if (variantIds.isEmpty()) return List.of();
        // A variant that was never received has no balance at all, so it is missing from this map.
        Set<UUID> withHistory = new HashSet<>(availability.available(variantIds).keySet());
        Map<UUID, ProductVariant> live = variants.findAllById(variantIds).stream()
                .filter(v -> !v.isDeleted() && v.isActive())
                .collect(Collectors.toMap(ProductVariant::getId, v -> v));
        List<Item> out = new ArrayList<>();
        for (VariantJob job : jobsList) {
            List<VariantJobItem> empty = itemsByJob.getOrDefault(job.getId(), List.of()).stream()
                    .filter(i -> i.getVariantId() != null && live.containsKey(i.getVariantId())
                            && !withHistory.contains(i.getVariantId()))
                    .toList();
            if (empty.isEmpty()) continue;
            String product = productNames.get(job.getProductId());
            out.add(new Item("job-stock-" + job.getId(), Severity.ACTION,
                    product + ": " + empty.size() + " new variant" + (empty.size() == 1 ? " has" : "s have") + " no stock",
                    "Customers see " + (empty.size() == 1 ? "it" : "them") + " as sold out: " + labels(empty) + ".",
                    job.getProductId(), product, job.getId(), ids(empty),
                    List.of(new Action("Add stock", ActionKind.ADD_STOCK)), job.getFinishedAt()));
        }
        return out;
    }

    private static String labels(List<VariantJobItem> its) {
        List<String> names = its.stream().map(VariantJobItem::label).toList();
        return names.size() <= 6 ? String.join(", ", names)
                : String.join(", ", names.subList(0, 6)) + " and " + (names.size() - 6) + " more";
    }

    private static List<UUID> ids(List<VariantJobItem> its) {
        return its.stream().map(VariantJobItem::getVariantId).filter(Objects::nonNull).toList();
    }
}
