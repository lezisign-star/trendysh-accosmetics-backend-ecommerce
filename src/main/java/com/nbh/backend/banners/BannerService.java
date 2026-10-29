package com.nbh.backend.banners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.repository.PromoCampaignRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.GeminiImageClient.Image;
import com.nbh.backend.imagecleanup.GeminiTextClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Occasion banners (owner, 2026-10-29). For the occasion on now, the job makes promotional pictures for the shop's
 * existing slots (the first card of "On offer today", a tile in product grids, each trade circle): Gemini writes a
 * brief and one line, edits a real product or category photo into a billboard scene with no words, then looks at it to
 * see where the words fit, and the container is chosen from that. Staff pick one version per slot; only picked banners
 * reach the shop, and only while their occasion is on. A new set is made when the occasion's offers change.
 */
@Slf4j
@Service
public class BannerService {

    /** Slots made per run, each with {@link #VERSIONS} versions. */
    static final int PER_RUN = 2;
    static final int VERSIONS = 2;
    /** How far ahead a scheduled sale is teased. */
    static final Duration TEASE_AHEAD = Duration.ofHours(72);

    private static final List<Banner.Status> TAKEN =
            List.of(Banner.Status.WAITING, Banner.Status.PICKED, Banner.Status.REJECTED, Banner.Status.FAILED);
    private static final List<Banner.Style> CARD_STYLES = List.of(Banner.Style.FAMILY, Banner.Style.CARTOON_JOKE,
            Banner.Style.PRODUCT_OFFER, Banner.Style.ANIMAL, Banner.Style.HAPPY_PEOPLE, Banner.Style.GREETING);

    private final OccasionRepository occasions;
    private final BannerRepository banners;
    private final PromoCampaignRepository campaigns;
    private final GeminiImageClient images;
    private final GeminiTextClient text;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig gemini;
    private final EntityManager em;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final boolean enabled;

    public BannerService(OccasionRepository occasions, BannerRepository banners, PromoCampaignRepository campaigns,
                         GeminiImageClient images, GeminiTextClient text, CloudinaryUploader cloudinary,
                         ImageCleanupConfig gemini, EntityManager em, ObjectMapper json,
                         PlatformTransactionManager transactions, ApplicationEventPublisher events, Clock clock,
                         @Value("${app.banners.enabled:true}") boolean enabled) {
        this.occasions = occasions;
        this.banners = banners;
        this.campaigns = campaigns;
        this.images = images;
        this.text = text;
        this.cloudinary = cloudinary;
        this.gemini = gemini;
        this.em = em;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.events = events;
        this.clock = clock;
        this.enabled = enabled;
    }

    // ---------------------------------------------------------------- shapes

    public record OccasionView(UUID id, String code, String name, Instant endsAt, String mainColor, String lightColor,
                               String moodLine) {}

    public record PublicBanner(UUID id, String slot, UUID categoryId, String container, String imageUrl, String line,
                               String textTone) {}

    public record Teaser(String name, Instant startsAt) {}

    /** What the shop shows: the occasion on now (or none), its picked banners, and a sale coming up. */
    public record ShopView(OccasionView occasion, List<PublicBanner> banners, Teaser teaser) {}

    public record AdminBanner(UUID id, UUID occasionId, String occasionName, String slot, UUID categoryId,
                              String categoryName, String style, String container, String imageUrl, String line,
                              String textTone, String status, String note, Instant createdAt, boolean occasionOn) {}

    public record AdminOccasion(UUID id, String code, String name, String kind, boolean enabled, int priority,
                                String moodLine, String startMd, String endMd, java.time.LocalDate startsOn,
                                java.time.LocalDate endsOn, String colors, boolean on, Instant endsAt) {}

    public record OccasionChange(Boolean enabled, Integer priority, String name, String moodLine, String startMd,
                                 String endMd, java.time.LocalDate startsOn, java.time.LocalDate endsOn) {}

    public record NewOccasion(String name, java.time.LocalDate startsOn, java.time.LocalDate endsOn, String moodLine,
                              String tone) {}

    public record MakeRequest(UUID occasionId, String slot, UUID categoryId) {}

    /** One slot to fill: where, about what, and the offers it is made for. */
    record Target(Banner.Slot slot, UUID categoryId, String subject, String photoUrl, String signature, boolean offerOn) {}

    // ---------------------------------------------------------------- the shop

    public ShopView shopView() {
        return tx.execute(s -> {
            Instant now = clock.instant();
            List<Occasion> on = OccasionCalendar.active(occasions.findAll(), now);
            Teaser teaser = campaigns.findAllByOrderByStartsAtDesc().stream()
                    .filter(c -> c.getStartsAt() != null && c.getStartsAt().isAfter(now)
                            && c.getStartsAt().isBefore(now.plus(TEASE_AHEAD)))
                    .min(Comparator.comparing(PromoCampaign::getStartsAt))
                    .map(c -> new Teaser(c.getName(), c.getStartsAt())).orElse(null);
            if (on.isEmpty()) return new ShopView(null, List.of(), teaser);
            Occasion top = on.get(0);
            List<PublicBanner> picked = banners.findByOccasionIdAndStatus(top.getId(), Banner.Status.PICKED).stream()
                    .map(b -> new PublicBanner(b.getId(), b.getSlot().name(), b.getCategoryId(), b.getContainer(),
                            b.getImageUrl(), b.getLine(), b.getTextTone()))
                    .toList();
            return new ShopView(view(top, now), picked, teaser);
        });
    }

    private static OccasionView view(Occasion o, Instant now) {
        String[] c = o.getColors().split(",");
        return new OccasionView(o.getId(), o.getCode(), o.getName(), OccasionCalendar.endsAt(o, now).orElse(null),
                c[0].strip(), c.length > 1 ? c[1].strip() : "#FFFFFF", o.getMoodLine());
    }

    // ---------------------------------------------------------------- the job

    @Scheduled(initialDelayString = "${app.banners.initial-delay-ms:180000}",
            fixedDelayString = "${app.banners.every-ms:600000}")
    public void runOnce() {
        if (!enabled || !gemini.geminiConfigured() || !cloudinary.configured()) return;
        Occasion top = tx.execute(s -> OccasionCalendar.active(occasions.findAll(), clock.instant()).stream()
                .findFirst().orElse(null));
        if (top == null) return;
        int made = 0;
        for (Target t : tx.execute(s -> targets())) {
            if (made >= PER_RUN) break;
            boolean taken = Boolean.TRUE.equals(tx.execute(s -> banners
                    .findByOccasionIdAndSlotAndStatusIn(top.getId(), t.slot(), TAKEN).stream()
                    .anyMatch(b -> Objects.equals(b.getCategoryId(), t.categoryId())
                            && t.signature().equals(b.getOfferSignature()))));
            if (taken) continue;
            try {
                makeSet(top, t);
                made++;
            } catch (QuotaReached e) {
                log.info("Banners: Gemini's limit reached; trying again next run");
                return;
            } catch (RuntimeException e) {
                log.warn("Banner for {} {} failed: {}", t.slot(), t.categoryId(), e.getMessage());
            }
        }
    }

    static class QuotaReached extends RuntimeException {
        QuotaReached(String message) {
            super(message);
        }
    }

    /** Every slot to fill now: the offers card, the grid tile, and one circle per top-level category with products. */
    @SuppressWarnings("unchecked")
    List<Target> targets() {
        Map<UUID, List<String>> dealsByTop = new LinkedHashMap<>();
        List<String> allDeals = new ArrayList<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                SELECT CAST(v.id AS VARCHAR), CAST(COALESCE(c.parent_id, c.id) AS VARCHAR)
                FROM product_variant v
                JOIN product p ON p.id = v.product_id AND p.is_published AND NOT p.deleted
                JOIN product_category pc ON pc.product_id = p.id AND pc.position = 0
                JOIN category c ON c.id = pc.category_id
                WHERE v.deal_price IS NOT NULL AND v.deal_ends_at > now() AND NOT v.deleted AND v.is_active
                ORDER BY v.deal_ends_at, v.id
                """).getResultList()) {
            String variant = (String) row[0];
            allDeals.add(variant);
            dealsByTop.computeIfAbsent(UUID.fromString((String) row[1]), k -> new ArrayList<>()).add(variant);
        }
        List<Target> out = new ArrayList<>();
        String firstDeal = allDeals.isEmpty() ? null : allDeals.get(0);
        String generalPhoto = firstDeal != null ? variantPhoto(firstDeal) : anyPhoto();
        String generalSubject = firstDeal != null ? variantName(firstDeal) : "the shop's beauty supplies";
        String all = signature(allDeals);
        out.add(new Target(Banner.Slot.OFFER_CARD, null, generalSubject, generalPhoto, all, !allDeals.isEmpty()));
        out.add(new Target(Banner.Slot.GRID_TILE, null, generalSubject, generalPhoto, all, !allDeals.isEmpty()));
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                SELECT CAST(t.id AS VARCHAR), t.name, t.image_url
                FROM category t
                WHERE t.parent_id IS NULL AND t.is_published AND EXISTS (
                    SELECT 1 FROM product_category pc
                    JOIN product p ON p.id = pc.product_id AND p.is_published AND NOT p.deleted
                    JOIN category c ON c.id = pc.category_id
                    WHERE COALESCE(c.parent_id, c.id) = t.id)
                ORDER BY t.sort_order, t.name
                """).getResultList()) {
            UUID id = UUID.fromString((String) row[0]);
            String name = (String) row[1];
            String image = row[2] instanceof String s && s.startsWith("http") ? s : categoryPhoto(id);
            List<String> deals = dealsByTop.getOrDefault(id, List.of());
            out.add(new Target(Banner.Slot.CIRCLE, id, name + " supplies", image, signature(deals), !deals.isEmpty()));
        }
        return out.stream().filter(t -> t.photoUrl() != null).toList();
    }

    private String variantPhoto(String variantId) {
        return first(em.createNativeQuery("""
                SELECT i.url FROM product_image i JOIN product_variant v ON v.id = CAST(:v AS UUID)
                WHERE i.variant_id = v.id OR (i.variant_id IS NULL AND i.product_id = v.product_id)
                ORDER BY (i.variant_id IS NULL), i.is_primary DESC, i.sort_order LIMIT 1
                """).setParameter("v", variantId).getResultList());
    }

    private String variantName(String variantId) {
        return first(em.createNativeQuery("""
                SELECT p.name FROM product_variant v JOIN product p ON p.id = v.product_id WHERE v.id = CAST(:v AS UUID)
                """).setParameter("v", variantId).getResultList());
    }

    private String categoryPhoto(UUID top) {
        return first(em.createNativeQuery("""
                SELECT i.url FROM product_image i
                JOIN product p ON p.id = i.product_id AND p.is_published AND NOT p.deleted
                JOIN product_category pc ON pc.product_id = p.id
                JOIN category c ON c.id = pc.category_id
                WHERE COALESCE(c.parent_id, c.id) = :t
                ORDER BY i.is_primary DESC, i.sort_order LIMIT 1
                """).setParameter("t", top).getResultList());
    }

    private String anyPhoto() {
        return first(em.createNativeQuery("""
                SELECT i.url FROM product_image i JOIN product p ON p.id = i.product_id AND p.is_published AND NOT p.deleted
                ORDER BY i.is_primary DESC, p.is_popular DESC, i.sort_order LIMIT 1
                """).getResultList());
    }

    private static String first(List<?> rows) {
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
    }

    /** The offers a set is made for: which products are on deal (a short hash, so it fits the column). */
    static String signature(List<String> variantIds) {
        if (variantIds.isEmpty()) return "none";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(String.join(",", new TreeSet<>(variantIds)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Makes {@link #VERSIONS} versions for one slot and files them for staff. */
    void makeSet(Occasion o, Target t) {
        boolean circle = t.slot() == Banner.Slot.CIRCLE;
        Image reference;
        try {
            reference = images.download(t.photoUrl());
        } catch (RuntimeException e) {
            record(o, t, Banner.Style.PRODUCT_OFFER, null, null, null, null, null, Banner.Status.FAILED,
                    "The photo could not be fetched: " + e.getMessage());
            return;
        }
        int seed = Math.abs(Objects.hash(o.getCode(), t.slot(), t.categoryId(), t.signature()));
        for (int v = 0; v < VERSIONS; v++) {
            Banner.Style style = circle ? Banner.Style.PRODUCT_OFFER : CARD_STYLES.get((seed + v) % CARD_STYLES.size());
            BannerPrompts.Calm calm = v % 2 == 0 ? BannerPrompts.Calm.BOTTOM : BannerPrompts.Calm.TOP;
            try {
                JsonNode brief = text.ask(null, BannerPrompts.brief(o, style, t.subject(), t.offerOn()),
                        BannerPrompts.briefSchema());
                String line = BannerPrompts.cleanLine(brief.path("line").asText(null), o.getMoodLine());
                Image picture = images.edit(reference,
                        BannerPrompts.picture(o, brief.path("scene").asText(""), t.subject(), circle, calm));
                JsonNode fit = text.ask(picture, BannerPrompts.fit(), BannerPrompts.fitSchema());
                String container = circle ? "circle" : BannerPrompts.container(fit.path("calmArea").asText("none"),
                        fit.path("busyness").asInt(10), fit.path("hasText").asBoolean(false));
                if (container == null || (circle && fit.path("hasText").asBoolean(false))) {
                    record(o, t, style, null, line, null, null, fit.toString(), Banner.Status.FAILED,
                            "The picture had no calm place for the words, or had words in it, so it was dropped.");
                    continue;
                }
                String tone = tone(picture, container);
                String url = cloudinary.upload(picture.bytes(), "banners",
                        "banner-" + o.getCode() + "-" + t.slot().name().toLowerCase(Locale.ROOT) + "-" + clock.millis())
                        .url();
                record(o, t, style, container, line, url, tone, fit.toString(), Banner.Status.WAITING, null);
            } catch (GeminiException e) {
                if (e.isQuotaExceeded()) throw new QuotaReached(e.getMessage());
                record(o, t, style, null, null, null, null, null, Banner.Status.FAILED, e.getMessage());
            }
        }
    }

    /** Light words on a dark word area, dark on a light one; over a darkened half they are always light. */
    static String tone(Image picture, String container) {
        if ("card-words-over".equals(container) || "circle".equals(container)) return "light";
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(picture.bytes()));
            if (img == null) return "light";
            int h = img.getHeight();
            int from = "card-words-top".equals(container) ? 0 : h * 2 / 3;
            int to = "card-words-top".equals(container) ? h / 3 : h;
            double sum = 0;
            long n = 0;
            for (int y = from; y < to; y += 4) {
                for (int x = 0; x < img.getWidth(); x += 4) {
                    int rgb = img.getRGB(x, y);
                    sum += (0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255)) / 255;
                    n++;
                }
            }
            return n > 0 && sum / n > 0.55 ? "dark" : "light";
        } catch (java.io.IOException e) {
            return "light";
        }
    }

    private void record(Occasion o, Target t, Banner.Style style, String container, String line, String url,
                        String tone, String fit, Banner.Status status, String note) {
        tx.executeWithoutResult(s -> {
            Banner b = new Banner();
            b.setOccasionId(o.getId());
            b.setSlot(t.slot());
            b.setCategoryId(t.categoryId());
            b.setStyle(style);
            b.setContainer(container);
            b.setLine(line);
            b.setImageUrl(url);
            b.setTextTone(tone);
            b.setFit(fit);
            b.setStatus(status);
            b.setNote(note);
            b.setOfferSignature(t.signature());
            banners.save(b);
        });
    }

    // ---------------------------------------------------------------- staff

    @SuppressWarnings("unchecked")
    public List<AdminBanner> forStaff() {
        return tx.execute(s -> {
            Instant now = clock.instant();
            Map<UUID, Occasion> byId = new HashMap<>();
            occasions.findAll().forEach(o -> byId.put(o.getId(), o));
            Map<UUID, String> categoryNames = new HashMap<>();
            for (Object[] row : (List<Object[]>) em.createNativeQuery(
                    "SELECT CAST(id AS VARCHAR), name FROM category WHERE parent_id IS NULL").getResultList()) {
                categoryNames.put(UUID.fromString((String) row[0]), (String) row[1]);
            }
            return banners.findByStatusInOrderByCreatedAtDesc(List.of(Banner.Status.WAITING, Banner.Status.PICKED))
                    .stream().map(b -> {
                        Occasion o = byId.get(b.getOccasionId());
                        return new AdminBanner(b.getId(), b.getOccasionId(), o == null ? "?" : o.getName(),
                                b.getSlot().name(), b.getCategoryId(),
                                b.getCategoryId() == null ? null : categoryNames.get(b.getCategoryId()),
                                b.getStyle().name(), b.getContainer(), b.getImageUrl(), b.getLine(), b.getTextTone(),
                                b.getStatus().name(), b.getNote(), b.getCreatedAt(),
                                o != null && OccasionCalendar.endsAt(o, now).isPresent());
                    }).toList();
        });
    }

    public long waitingCount() {
        return banners.countByStatus(Banner.Status.WAITING);
    }

    /** Picks this version: it shows during its occasion, and the version picked before for the same slot retires. */
    public void pick(UUID id, String staffName) {
        tx.executeWithoutResult(s -> {
            Banner b = banners.findById(id).orElseThrow(() -> new NotFoundException("No such banner."));
            if (b.getStatus() != Banner.Status.WAITING && b.getStatus() != Banner.Status.PICKED) {
                throw new UnprocessableException("This banner can no longer be picked.");
            }
            for (Banner other : banners.findByOccasionIdAndStatus(b.getOccasionId(), Banner.Status.PICKED)) {
                if (!other.getId().equals(b.getId()) && other.getSlot() == b.getSlot()
                        && Objects.equals(other.getCategoryId(), b.getCategoryId())) {
                    other.setStatus(Banner.Status.RETIRED);
                }
            }
            b.setStatus(Banner.Status.PICKED);
            b.setDecidedBy(staffName);
            b.setDecidedAt(clock.instant());
        });
        changed();
    }

    public void reject(UUID id, String staffName) {
        tx.executeWithoutResult(s -> {
            Banner b = banners.findById(id).orElseThrow(() -> new NotFoundException("No such banner."));
            b.setStatus(b.getStatus() == Banner.Status.PICKED ? Banner.Status.RETIRED : Banner.Status.REJECTED);
            b.setDecidedBy(staffName);
            b.setDecidedAt(clock.instant());
        });
        changed();
    }

    /** Staff's own words for the line (the offer is still added by the shop from live data). */
    public void editLine(UUID id, String line) {
        String clean = line == null ? "" : line.replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) throw new UnprocessableException("Write a line for the banner.");
        if (clean.length() > 60) throw new UnprocessableException("Keep the line to 60 characters or fewer.");
        tx.executeWithoutResult(s -> banners.findById(id)
                .orElseThrow(() -> new NotFoundException("No such banner.")).setLine(clean));
        changed();
    }

    /** "Make more" for one slot, now (staff wait for it: about a minute). */
    public void makeNow(MakeRequest in) {
        if (!gemini.geminiConfigured() || !cloudinary.configured()) {
            throw new UnprocessableException("Gemini or photo storage is not set up, so banners can't be made.");
        }
        Banner.Slot slot;
        try {
            slot = Banner.Slot.valueOf(in.slot());
        } catch (RuntimeException e) {
            throw new UnprocessableException("Unknown slot.");
        }
        Occasion o = tx.execute(s -> occasions.findById(in.occasionId())
                .orElseThrow(() -> new NotFoundException("No such occasion.")));
        Target t = tx.execute(s -> targets()).stream()
                .filter(x -> x.slot() == slot && Objects.equals(x.categoryId(), in.categoryId()))
                .findFirst().orElseThrow(() -> new UnprocessableException("There is nothing to make this banner from."));
        try {
            makeSet(o, t);
        } catch (QuotaReached e) {
            throw new UnprocessableException("Gemini's limit for now is used up. Try again later.");
        }
    }

    // ---------------------------------------------------------------- occasions

    public List<AdminOccasion> occasionsForStaff() {
        Instant now = clock.instant();
        return tx.execute(s -> occasions.findAllByOrderByPriorityDescNameAsc().stream()
                .map(o -> {
                    Optional<Instant> ends = OccasionCalendar.endsAt(o, now);
                    return new AdminOccasion(o.getId(), o.getCode(), o.getName(), o.getKind().name(), o.isEnabled(),
                            o.getPriority(), o.getMoodLine(), o.getStartMd(), o.getEndMd(), o.getStartsOn(),
                            o.getEndsOn(), o.getColors(), ends.isPresent(), ends.orElse(null));
                }).toList());
    }

    public void changeOccasion(UUID id, OccasionChange in) {
        tx.executeWithoutResult(s -> {
            Occasion o = occasions.findById(id).orElseThrow(() -> new NotFoundException("No such occasion."));
            if (in.enabled() != null) o.setEnabled(in.enabled());
            if (in.priority() != null) o.setPriority(Math.max(0, Math.min(100, in.priority())));
            if (in.name() != null && !in.name().isBlank()) o.setName(in.name().strip());
            if (in.moodLine() != null && !in.moodLine().isBlank()) o.setMoodLine(in.moodLine().strip());
            if (o.getKind() == Occasion.Kind.YEARLY) {
                if (in.startMd() != null) o.setStartMd(monthDay(in.startMd()));
                if (in.endMd() != null) o.setEndMd(monthDay(in.endMd()));
            }
            if (o.getKind() == Occasion.Kind.DATES) {
                if (in.startsOn() != null) o.setStartsOn(in.startsOn());
                if (in.endsOn() != null) o.setEndsOn(in.endsOn());
                if (o.getEndsOn().isBefore(o.getStartsOn())) throw new UnprocessableException("It must end after it starts.");
            }
        });
        changed();
    }

    /** Staff's own occasion on fixed dates (a shop anniversary); it wins over the built-in ones while it is on. */
    public void addOccasion(NewOccasion in) {
        if (in == null || in.name() == null || in.name().isBlank()) throw new UnprocessableException("Give the occasion a name.");
        if (in.startsOn() == null || in.endsOn() == null || in.endsOn().isBefore(in.startsOn())) {
            throw new UnprocessableException("Give the first and last day, the last on or after the first.");
        }
        tx.executeWithoutResult(s -> {
            String base = in.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
            String code = base.isEmpty() ? "occasion" : base;
            for (int i = 2; occasions.existsByCode(code); i++) code = base + "-" + i;
            Occasion o = new Occasion();
            o.setCode(code);
            o.setName(in.name().strip());
            o.setKind(Occasion.Kind.DATES);
            o.setStartsOn(in.startsOn());
            o.setEndsOn(in.endsOn());
            o.setPriority(60);
            o.setTone(in.tone() == null || in.tone().isBlank() ? in.name().strip() + " at Trendysh Accosmetic." : in.tone().strip());
            o.setMoodLine(in.moodLine() == null || in.moodLine().isBlank() ? in.name().strip() : in.moodLine().strip());
            o.setColors("#C2185B,#FFF1F5");
            occasions.save(o);
        });
        changed();
    }

    private static String monthDay(String raw) {
        try {
            return java.time.MonthDay.parse("--" + raw.strip()).toString().substring(2);
        } catch (RuntimeException e) {
            throw new UnprocessableException("Write the day as MM-DD, e.g. 11-01.");
        }
    }

    private void changed() {
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
    }
}
