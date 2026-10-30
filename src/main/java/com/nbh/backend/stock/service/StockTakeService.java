package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.dto.AttachImageRequest;
import com.nbh.backend.catalog.dto.ProductDto;
import com.nbh.backend.catalog.dto.SaveProductRequest;
import com.nbh.backend.catalog.entity.StockBasis;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.ProductService;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.entity.StockCount;
import com.nbh.backend.stock.entity.StockCountLine;
import com.nbh.backend.stock.entity.StockCountStatus;
import com.nbh.backend.stock.entity.StockTake;
import com.nbh.backend.stock.repository.StockCountRepository;
import com.nbh.backend.stock.repository.StockTakeRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A stock take, category by category (owner, 2026-10-30). One session for the shop; each top-level category is its own
 * stock count (StockCountService), opened by whoever starts it and joined by anyone else counting it, and applied as
 * soon as it is finished, so the shop keeps selling everything else. On a category's screen its items are grouped by
 * subcategory; each item is counted, marked "None on the shelf" (0) or skipped (left as it was); a whole subcategory can
 * be set to one number for every item in it; and a product found on the shelf that is not in the system yet is added
 * there, live in the shop at once, with its count. A category can only be finished once every item is counted or skipped.
 */
@Service
public class StockTakeService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final UUID NO_ID = new UUID(0L, 0L);

    private final StockTakeRepository takes;
    private final StockCountRepository counts;
    private final StockCountService countService;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final ProductService products;
    private final EntityManager em;
    private final Clock clock;

    public StockTakeService(StockTakeRepository takes, StockCountRepository counts, StockCountService countService,
                            ProductVariantRepository variants, CategoryRepository categories, ProductService products,
                            EntityManager em, Clock clock) {
        this.takes = takes;
        this.counts = counts;
        this.countService = countService;
        this.variants = variants;
        this.categories = categories;
        this.products = products;
        this.em = em;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- shapes

    public record CategoryProgress(UUID categoryId, String name, int items, String status, UUID countId, int counted,
                                   int skipped, int total, String countedBy, Instant doneAt) {}

    public record TakeView(UUID id, String takeNo, String status, Instant startedAt, String startedBy,
                           List<CategoryProgress> categories) {}

    public record LineView(UUID variantId, UUID productId, String name, String option, String sku, String photoUrl,
                           BigDecimal systemQty, boolean untracked, BigDecimal countedQty, boolean skipped) {}

    /** {@code subcategoryId} null: the products filed straight under the category. */
    public record Group(UUID subcategoryId, String name, List<LineView> lines) {}

    public record CountView(UUID countId, UUID takeId, UUID categoryId, String categoryName, String status,
                            String countedBy, int counted, int skipped, int total, List<Group> groups) {}

    public record SetAll(UUID subcategoryId, boolean wholeCategory, BigDecimal qty) {}

    public record NewItem(String name, String price, UUID subcategoryId, BigDecimal qty, String imageUrl) {}

    // ---------------------------------------------------------------- the session

    @Transactional(readOnly = true)
    public Optional<TakeView> current() {
        return takes.findFirstByStatusOrderByCreatedAtDesc("OPEN").map(this::view);
    }

    /** Starts the shop's stock take, or returns the one already open (there is one at a time). */
    @Transactional
    public TakeView start(StaffUser staff) {
        StockTake take = takes.findFirstByStatusOrderByCreatedAtDesc("OPEN").orElseGet(() -> {
            StockTake t = new StockTake();
            t.setTakeNo("ST-" + LocalDate.now(clock).format(DATE) + "-"
                    + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT));
            t.setCreatedByUserId(staff == null ? null : staff.getId());
            t.setCreatedByName(staff == null ? null : staff.getFullName());
            return takes.save(t);
        });
        return view(take);
    }

    @Transactional(readOnly = true)
    public TakeView get(UUID takeId) {
        return view(take(takeId));
    }

    /** Ends the stock take. Categories still being counted keep their counts; nothing is applied by closing. */
    @Transactional
    public TakeView close(UUID takeId) {
        StockTake take = take(takeId);
        take.setStatus("CLOSED");
        take.setClosedAt(clock.instant());
        return view(take);
    }

    @SuppressWarnings("unchecked")
    private TakeView view(StockTake take) {
        Map<UUID, StockCount> byCategory = new HashMap<>();
        for (StockCount c : (List<StockCount>) em.createQuery(
                        "SELECT c FROM StockCount c WHERE c.takeId = :t AND c.status <> :cancelled ORDER BY c.createdAt")
                .setParameter("t", take.getId()).setParameter("cancelled", StockCountStatus.CANCELLED).getResultList()) {
            byCategory.put(c.getCategoryId(), c);
        }
        List<CategoryProgress> out = new ArrayList<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery(
                "SELECT CAST(id AS VARCHAR), name FROM category WHERE parent_id IS NULL ORDER BY sort_order, name")
                .getResultList()) {
            UUID id = UUID.fromString((String) row[0]);
            StockCount c = byCategory.get(id);
            if (c == null) {
                int items = variants.idsInScope(false, List.of(id), List.of(NO_ID)).size();
                if (items == 0) continue;
                out.add(new CategoryProgress(id, (String) row[1], items, "WAITING", null, 0, 0, items, null, null));
                continue;
            }
            int counted = (int) c.getLines().stream().filter(StockCountLine::isCounted).count();
            int skipped = (int) c.getLines().stream().filter(StockCountLine::isSkipped).count();
            String status = c.getStatus() == StockCountStatus.APPLIED ? "DONE" : "COUNTING";
            out.add(new CategoryProgress(id, (String) row[1], c.getLines().size(), status, c.getId(), counted, skipped,
                    c.getLines().size(), c.getCountedByNames(), c.getAppliedAt()));
        }
        return new TakeView(take.getId(), take.getTakeNo(), take.getStatus(), take.getCreatedAt(),
                take.getCreatedByName(), out);
    }

    private StockTake take(UUID takeId) {
        return takes.findById(takeId).orElseThrow(() -> new NotFoundException("No such stock take."));
    }

    // ---------------------------------------------------------------- one category

    /** Opens a category's count, or joins the one already being counted (several people can count one category). */
    @Transactional
    @SuppressWarnings("unchecked")
    public CountView openCategory(UUID takeId, UUID categoryId, StaffUser staff) {
        StockTake take = take(takeId);
        if (!"OPEN".equals(take.getStatus())) throw new UnprocessableException("This stock take has ended.");
        var category = categories.findById(categoryId).orElseThrow(() -> new NotFoundException("No such category."));
        StockCount count = ((List<StockCount>) em.createQuery(
                        "SELECT c FROM StockCount c WHERE c.takeId = :t AND c.categoryId = :c AND c.status <> :cancelled")
                .setParameter("t", takeId).setParameter("c", categoryId)
                .setParameter("cancelled", StockCountStatus.CANCELLED).getResultList()).stream().findFirst().orElse(null);
        if (count == null) {
            count = countService.createForScope(false, List.of(categoryId), List.of(), false,
                    "Stock take " + take.getTakeNo() + ": " + category.getName(), staff == null ? null : staff.getId());
            count.setTakeId(takeId);
            count.setCategoryId(categoryId);
        }
        join(count, staff);
        counts.save(count);
        return countView(count.getId());
    }

    private static void join(StockCount count, StaffUser staff) {
        if (staff == null) return;
        String name = staff.getFullName();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (count.getCountedByNames() != null) {
            Arrays.stream(count.getCountedByNames().split(",")).map(String::strip).filter(s -> !s.isEmpty())
                    .forEach(names::add);
        }
        names.add(name);
        String joined = String.join(", ", names);
        count.setCountedByNames(joined.length() > 500 ? joined.substring(0, 500) : joined);
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public CountView countView(UUID countId) {
        StockCount count = countService.get(countId);
        if (count.getTakeId() == null) throw new UnprocessableException("That count is not part of a stock take.");
        UUID top = count.getCategoryId();
        String topName = categories.findById(top).map(c -> c.getName()).orElse("?");
        List<UUID> variantIds = count.getLines().stream().map(l -> l.getVariant().getId()).toList();

        // Each line's subcategory (the product's category under this one), and its photo.
        Map<UUID, UUID> subOf = new HashMap<>();
        Map<UUID, String> subName = new LinkedHashMap<>();
        Map<UUID, String> photoOf = new HashMap<>();
        if (!variantIds.isEmpty()) {
            for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                    SELECT CAST(v.id AS VARCHAR), CAST(c.id AS VARCHAR), c.name
                    FROM product_variant v
                    JOIN product_category pc ON pc.product_id = v.product_id
                    JOIN category c ON c.id = pc.category_id AND c.parent_id = :top
                    WHERE v.id IN (:ids)
                    ORDER BY c.sort_order, c.name
                    """).setParameter("top", top).setParameter("ids", variantIds).getResultList()) {
                UUID v = UUID.fromString((String) row[0]);
                UUID c = UUID.fromString((String) row[1]);
                subOf.putIfAbsent(v, c);
                subName.putIfAbsent(c, (String) row[2]);
            }
            for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                    SELECT DISTINCT ON (v.id) CAST(v.id AS VARCHAR), i.url
                    FROM product_variant v JOIN product_image i
                      ON i.variant_id = v.id OR (i.variant_id IS NULL AND i.product_id = v.product_id)
                    WHERE v.id IN (:ids)
                    ORDER BY v.id, (i.variant_id IS NULL), i.is_primary DESC, i.sort_order
                    """).setParameter("ids", variantIds).getResultList()) {
                photoOf.put(UUID.fromString((String) row[0]), (String) row[1]);
            }
        }

        Map<UUID, List<LineView>> grouped = new LinkedHashMap<>();
        subName.keySet().forEach(k -> grouped.put(k, new ArrayList<>()));
        List<LineView> direct = new ArrayList<>();
        List<StockCountLine> lines = new ArrayList<>(count.getLines());
        lines.sort(Comparator.comparing((StockCountLine l) -> l.getVariant().getProduct().getName(),
                String.CASE_INSENSITIVE_ORDER).thenComparing(l -> l.getVariant().getSortOrder()));
        for (StockCountLine l : lines) {
            var v = l.getVariant();
            String option = v.shortOption();
            LineView view = new LineView(v.getId(), v.getProduct().getId(), v.getProduct().getName(),
                    option == null || option.isBlank() || option.equalsIgnoreCase("Default") ? null : option,
                    v.getSku(), photoOf.get(v.getId()), l.getSystemQtyAtStart(),
                    v.getStockBasis() == StockBasis.NONE && count.getStatus() != StockCountStatus.APPLIED,
                    l.getCountedQty(), l.isSkipped());
            UUID sub = subOf.get(v.getId());
            if (sub == null) direct.add(view);
            else grouped.get(sub).add(view);
        }
        List<Group> groups = new ArrayList<>();
        grouped.forEach((id, ls) -> {
            if (!ls.isEmpty()) groups.add(new Group(id, subName.get(id), ls));
        });
        if (!direct.isEmpty()) groups.add(new Group(null, groups.isEmpty() ? topName : "Other " + topName, direct));
        int counted = (int) count.getLines().stream().filter(StockCountLine::isCounted).count();
        int skipped = (int) count.getLines().stream().filter(StockCountLine::isSkipped).count();
        return new CountView(count.getId(), count.getTakeId(), top, topName, count.getStatus().name(),
                count.getCountedByNames(), counted, skipped, count.getLines().size(), groups);
    }

    @Transactional
    public CountView record(UUID countId, UUID variantId, BigDecimal qty, StaffUser staff) {
        if (qty == null) throw new UnprocessableException("Type how many there are.");
        StockCount count = countService.recordCount(countId, variantId, qty, null, staffId(staff));
        join(count, staff);
        return countView(countId);
    }

    @Transactional
    public CountView skip(UUID countId, UUID variantId, StaffUser staff) {
        StockCount count = countService.skip(countId, variantId, staffId(staff));
        join(count, staff);
        return countView(countId);
    }

    /** "Set every item here to N": each item of the subcategory (or of the whole category) gets that number. */
    @Transactional
    public CountView setAll(UUID countId, SetAll in, StaffUser staff) {
        if (in == null || in.qty() == null || in.qty().signum() < 0) {
            throw new UnprocessableException("Type the number for every item here.");
        }
        CountView view = countView(countId);
        Map<UUID, BigDecimal> each = new LinkedHashMap<>();
        for (Group g : view.groups()) {
            if (in.wholeCategory() || Objects.equals(g.subcategoryId(), in.subcategoryId())) {
                g.lines().forEach(l -> each.put(l.variantId(), in.qty()));
            }
        }
        if (each.isEmpty()) throw new UnprocessableException("There are no items there to set.");
        StockCount count = countService.recordCounts(countId, each, staffId(staff));
        join(count, staff);
        return countView(countId);
    }

    /** A product found on the shelf that is not in the system: added in this category, live in the shop, with its count. */
    @Transactional
    public CountView addItem(UUID countId, NewItem in, StaffUser staff) {
        if (in == null || in.name() == null || in.name().isBlank()) throw new UnprocessableException("Give the item its name.");
        if (in.qty() == null || in.qty().signum() < 0) throw new UnprocessableException("Type how many there are.");
        BigDecimal price;
        try {
            price = new BigDecimal(in.price() == null ? "" : in.price().replace(",", "").strip());
        } catch (NumberFormatException e) {
            throw new UnprocessableException("Type the price in KSh, e.g. 450.");
        }
        if (price.signum() <= 0) throw new UnprocessableException("The price must be more than 0.");
        StockCount count = countService.get(countId);
        if (count.getTakeId() == null) throw new UnprocessableException("That count is not part of a stock take.");
        UUID filedUnder = in.subcategoryId() != null ? in.subcategoryId() : count.getCategoryId();
        var sub = categories.findById(filedUnder).orElseThrow(() -> new NotFoundException("No such category."));
        if (!sub.getId().equals(count.getCategoryId())
                && (sub.getParent() == null || !sub.getParent().getId().equals(count.getCategoryId()))) {
            throw new UnprocessableException("That subcategory is not part of the category being counted.");
        }
        ProductDto made = products.saveProduct(null, new SaveProductRequest(null, in.name().strip(), null, null, null,
                null, List.of(filedUnder), true, null, null,
                List.of(new SaveProductRequest.VariantInput(null, "Default", null, price.toPlainString(), true))), staff);
        UUID variantId = made.variants().get(0).id();
        if (in.imageUrl() != null && in.imageUrl().startsWith("https://")) {
            products.attachImage(made.id(), new AttachImageRequest(in.imageUrl(), null, variantId, in.name().strip(),
                    true, null, null, null));
        }
        StockCount updated = countService.addLine(countId, variantId, in.qty(), staffId(staff));
        join(updated, staff);
        return countView(countId);
    }

    /**
     * Finishes a category: every item counted or skipped, then the counted ones are applied. Sales made while the
     * category was being counted are kept (the difference the counter found is applied to today's figure).
     */
    @Transactional
    public CountView finish(UUID countId, StaffUser staff) {
        StockCount count = countService.get(countId);
        if (count.getTakeId() == null) throw new UnprocessableException("That count is not part of a stock take.");
        long left = count.getLines().stream().filter(l -> !l.isCounted() && !l.isSkipped()).count();
        if (left > 0) {
            throw new UnprocessableException(left + (left == 1 ? " item is" : " items are")
                    + " still to count. Count them, mark them none on the shelf, or skip them.");
        }
        boolean anyCounted = count.getLines().stream().anyMatch(StockCountLine::isCounted);
        if (!anyCounted) {
            countService.cancel(countId);
            return countView(countId);
        }
        Map<UUID, StockCountService.ConflictResolution> keepSales = count.getLines().stream()
                .filter(StockCountLine::isCounted)
                .collect(Collectors.toMap(l -> l.getVariant().getId(),
                        l -> StockCountService.ConflictResolution.KEEP_DIFFERENCE));
        countService.apply(countId, keepSales, staffId(staff));
        return countView(countId);
    }

    private static UUID staffId(StaffUser staff) {
        return staff == null ? null : staff.getId();
    }
}
