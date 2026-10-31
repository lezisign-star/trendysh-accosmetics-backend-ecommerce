package com.nbh.backend.banners;

import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * The banner builder (owner, 2026-10-31). Staff make each banner on purpose: where it shows ({@link PromoBanner.Placement}),
 * what a tap opens, up to four featured products, its words and offer, a picture, and its dates; then they approve it.
 * An approved banner is scheduled, live or ended by its dates; editing one takes it back to a draft to approve again, so
 * nothing reaches customers unseen. Pictures are only made when staff ask ({@link PromoPictureMaker}), because each costs.
 */
@Service
public class PromoBannerService {

    /** Featured products drawn on a banner. */
    static final int MAX_PRODUCTS = 4;
    /** Pictures one "Generate" may ask for. */
    static final int MAX_PER_GENERATE = 3;

    private static final String SHOP_MAIN = "#C2185B";
    private static final String SHOP_LIGHT = "#FFF1F5";

    private final PromoBannerRepository banners;
    private final PromoBannerPictureRepository pictures;
    private final OccasionRepository occasions;
    private final PromoPictureMaker maker;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig gemini;
    /** Set by Spring; tests and hand-built instances may leave it out. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.imagecleanup.GeminiTextClient text;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PromoBannerService(PromoBannerRepository banners, PromoBannerPictureRepository pictures,
                              OccasionRepository occasions, PromoPictureMaker maker, CloudinaryUploader cloudinary,
                              ImageCleanupConfig gemini, EntityManager em, PlatformTransactionManager transactions,
                              ApplicationEventPublisher events, Clock clock) {
        this.banners = banners;
        this.pictures = pictures;
        this.occasions = occasions;
        this.maker = maker;
        this.cloudinary = cloudinary;
        this.gemini = gemini;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- shapes

    public record Theme(UUID occasionId, String name, String mainColor, String lightColor) {}

    public record ProductCard(UUID variantId, String name, String imageUrl, BigDecimal price, BigDecimal dealPrice,
                              Instant dealEndsAt) {}

    public record Link(String type, UUID categoryId, String categorySlug, String categoryName, UUID variantId,
                       String productName) {}

    public record PictureView(UUID id, String source, String status, String imageUrl, String brief, String note,
                              Instant createdAt) {}

    /** Everything staff see and edit. {@code state}: DRAFT, SCHEDULED, LIVE, ENDED or PAUSED; {@code missing}: what stops approval. */
    public record AdminView(UUID id, String name, String placement, UUID categoryId, String categoryName, Link link,
                            String headline, String subline, String ctaLabel, String offerMode, String offerText,
                            String offer, boolean showCountdown, Instant countdownEndsAt, Theme theme, String textTone,
                            String imageUrl, int focusX, int focusY, Instant startsAt, Instant endsAt, int sortOrder,
                            String status, String state, List<ProductCard> products, List<PictureView> pictures,
                            List<String> missing, String createdBy, String approvedBy, Instant approvedAt,
                            Instant updatedAt, Style style) {}

    /** What the shop draws. */
    public record PublicView(UUID id, String placement, UUID categoryId, String categorySlug, Link link,
                             String headline, String subline, String ctaLabel, String offer, Instant countdownEndsAt,
                             Theme theme, String textTone, String imageUrl, int focusX, int focusY,
                             List<ProductCard> products, Style style) {}

    public record NewBanner(String name, String placement) {}

    /** {@code style}: the advanced look; null leaves it as it is, an empty one goes back to the automatic look. */
    public record Edit(String name, String placement, UUID categoryId, String linkType, UUID linkCategoryId,
                       UUID linkVariantId, String headline, String subline, String ctaLabel, String offerMode,
                       String offerText, Boolean showCountdown, UUID occasionId, String textTone, Integer focusX,
                       Integer focusY, Instant startsAt, Instant endsAt, Integer sortOrder, List<UUID> productIds,
                       Style style) {}

    /**
     * The advanced look (owner, 2026-10-31), every part optional (null = automatic): colours as "#rrggbb";
     * {@code headlineSize} S, M or L; {@code align} left or center (hero and strip); {@code shade} none, light or strong
     * (behind the words); {@code font} sans or display.
     */
    public record Style(String headlineColor, String textColor, String headlineSize, String align, String shade,
                        String backgroundColor, String buttonColor, String buttonTextColor, String font) {

        private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

        /** Only what the shop knows how to draw; null when nothing is left (the automatic look). */
        static Style clean(Style in) {
            if (in == null) return null;
            Style s = new Style(color(in.headlineColor), color(in.textColor),
                    oneOf(in.headlineSize, "S", "M", "L"), oneOf(in.align, "left", "center"),
                    oneOf(in.shade, "none", "light", "strong"), color(in.backgroundColor), color(in.buttonColor),
                    color(in.buttonTextColor), oneOf(in.font, "sans", "display"));
            boolean empty = s.headlineColor == null && s.textColor == null && s.headlineSize == null
                    && s.align == null && s.shade == null && s.backgroundColor == null && s.buttonColor == null
                    && s.buttonTextColor == null && s.font == null;
            return empty ? null : s;
        }

        private static String color(String v) {
            if (v == null || v.isBlank()) return null;
            if (!HEX.matcher(v.strip()).matches()) {
                throw new UnprocessableException("Colours are written like #C2185B: \"" + v + "\" isn't one.");
            }
            return v.strip().toUpperCase(Locale.ROOT);
        }

        private static String oneOf(String v, String... allowed) {
            if (v == null || v.isBlank()) return null;
            for (String a : allowed) if (a.equalsIgnoreCase(v.strip())) return a;
            throw new UnprocessableException("\"" + v + "\" isn't one of " + String.join(", ", allowed) + ".");
        }
    }

    public record Generate(String brief, Integer count) {}

    public record Upload(String imageUrl) {}

    public record WordsRequest(String hint) {}

    /** One set of words Gemini suggests; staff pick one and can still change it. */
    public record WordSuggestion(String headline, String subline, String ctaLabel) {}

    // ---------------------------------------------------------------- staff: banners

    public List<AdminView> list() {
        return tx.execute(s -> {
            Context c = context();
            return banners.findAllByOrderByUpdatedAtDesc().stream().map(b -> admin(b, c)).toList();
        });
    }

    public AdminView get(UUID id) {
        return tx.execute(s -> admin(find(id), context()));
    }

    public AdminView create(NewBanner in, String staff) {
        PromoBanner.Placement placement = placement(in == null ? null : in.placement());
        String name = in.name() == null || in.name().isBlank() ? defaultName(placement) : clip(in.name(), 120);
        UUID id = tx.execute(s -> {
            PromoBanner b = new PromoBanner();
            b.setName(name);
            b.setPlacement(placement);
            b.setCtaLabel(placement == PromoBanner.Placement.CIRCLE ? null : "Shop now");
            // the pictures are bright pastel studio shots (PromoBannerPrompts): dark words read best on them
            b.setTextTone("dark");
            b.setCreatedBy(staff);
            Instant now = clock.instant();
            b.setStartsAt(now);
            b.setEndsAt(now.plus(java.time.Duration.ofDays(7)));
            return banners.save(b).getId();
        });
        return get(id);
    }

    public AdminView update(UUID id, Edit in) {
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            if (in.name() != null) b.setName(required(in.name(), 120, "Give the banner a name."));
            if (in.placement() != null) b.setPlacement(placement(in.placement()));
            b.setCategoryId(in.categoryId());
            if (in.linkType() != null) b.setLinkType(enumOf(PromoBanner.LinkType.class, in.linkType(), "Unknown link."));
            b.setLinkCategoryId(in.linkCategoryId());
            b.setLinkVariantId(in.linkVariantId());
            b.setHeadline(optional(in.headline(), 80));
            b.setSubline(optional(in.subline(), 140));
            b.setCtaLabel(optional(in.ctaLabel(), 30));
            if (in.offerMode() != null) {
                b.setOfferMode(enumOf(PromoBanner.OfferMode.class, in.offerMode(), "Unknown offer setting."));
            }
            b.setOfferText(optional(in.offerText(), 40));
            if (in.showCountdown() != null) b.setShowCountdown(in.showCountdown());
            b.setOccasionId(in.occasionId());
            if (in.textTone() != null) b.setTextTone("dark".equals(in.textTone()) ? "dark" : "light");
            if (in.focusX() != null) b.setFocusX((short) Math.max(0, Math.min(100, in.focusX())));
            if (in.focusY() != null) b.setFocusY((short) Math.max(0, Math.min(100, in.focusY())));
            b.setStartsAt(in.startsAt());
            b.setEndsAt(in.endsAt());
            if (in.sortOrder() != null) b.setSortOrder(Math.max(0, in.sortOrder()));
            if (in.productIds() != null) setProducts(b.getId(), in.productIds());
            if (in.style() != null) b.setStyle(writeStyle(Style.clean(in.style())));
            backToDraft(b);
        });
        changed();
        return get(id);
    }

    /** Checks everything a customer would see, then lets it show between its dates. */
    public AdminView approve(UUID id, String staff) {
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            List<String> missing = missing(b, products(List.of(b.getId())).getOrDefault(b.getId(), List.of()));
            if (!missing.isEmpty()) throw new UnprocessableException("Not ready to approve: " + String.join("; ", missing));
            b.setStatus(PromoBanner.Status.APPROVED);
            b.setApprovedBy(staff);
            b.setApprovedAt(clock.instant());
        });
        changed();
        return get(id);
    }

    public AdminView pause(UUID id) {
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            if (b.getStatus() != PromoBanner.Status.APPROVED) throw new UnprocessableException("Only an approved banner can be paused.");
            b.setStatus(PromoBanner.Status.PAUSED);
        });
        changed();
        return get(id);
    }

    public AdminView resume(UUID id) {
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            if (b.getStatus() != PromoBanner.Status.PAUSED) throw new UnprocessableException("Only a paused banner can be resumed.");
            b.setStatus(PromoBanner.Status.APPROVED);
        });
        changed();
        return get(id);
    }

    /** Takes it down now, keeping it (and its approval) in Ended. */
    public AdminView endNow(UUID id) {
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            Instant now = clock.instant();
            if (b.getStartsAt() == null || b.getStartsAt().isAfter(now)) b.setStartsAt(now);
            b.setEndsAt(now);
        });
        changed();
        return get(id);
    }

    /** A new draft with the same words, products, picture and link, for next week's version of a banner. */
    public AdminView duplicate(UUID id, String staff) {
        UUID copy = tx.execute(s -> {
            PromoBanner from = find(id);
            PromoBanner b = new PromoBanner();
            b.setName(clip(from.getName() + " (copy)", 120));
            b.setPlacement(from.getPlacement());
            b.setCategoryId(from.getCategoryId());
            b.setLinkType(from.getLinkType());
            b.setLinkCategoryId(from.getLinkCategoryId());
            b.setLinkVariantId(from.getLinkVariantId());
            b.setHeadline(from.getHeadline());
            b.setSubline(from.getSubline());
            b.setCtaLabel(from.getCtaLabel());
            b.setOfferMode(from.getOfferMode());
            b.setOfferText(from.getOfferText());
            b.setShowCountdown(from.isShowCountdown());
            b.setOccasionId(from.getOccasionId());
            b.setTextTone(from.getTextTone());
            b.setImageUrl(from.getImageUrl());
            b.setFocusX(from.getFocusX());
            b.setFocusY(from.getFocusY());
            b.setSortOrder(from.getSortOrder());
            b.setStyle(from.getStyle());
            b.setCreatedBy(staff);
            UUID newId = banners.save(b).getId();
            setProducts(newId, products(List.of(id)).getOrDefault(id, List.of()).stream().map(ProductCard::variantId).toList());
            if (from.getImageUrl() != null) {
                PromoBannerPicture p = new PromoBannerPicture();
                p.setBannerId(newId);
                p.setSource(PromoBannerPicture.Source.UPLOAD);
                p.setStatus(PromoBannerPicture.Status.READY);
                p.setImageUrl(from.getImageUrl());
                p.setNote("Copied from \"" + from.getName() + "\"");
                pictures.save(p);
            }
            return newId;
        });
        return get(copy);
    }

    /** A draft goes for good; anything that was approved is ended instead, so its history stays. */
    public void delete(UUID id) {
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            if (b.getApprovedAt() != null) {
                throw new UnprocessableException("This banner was approved before; end it instead so its history stays.");
            }
            banners.delete(b);
        });
        changed();
    }

    // ---------------------------------------------------------------- staff: pictures

    /** Asks Gemini for {@code count} pictures in the background; each shows as "making" until it is ready or fails. */
    public AdminView generate(UUID id, Generate in) {
        if (!gemini.geminiConfigured() || !cloudinary.configured()) {
            throw new UnprocessableException("Gemini or photo storage is not set up, so pictures can't be made.");
        }
        int count = Math.max(1, Math.min(MAX_PER_GENERATE, in == null || in.count() == null ? 1 : in.count()));
        String brief = in == null ? null : optional(in.brief(), 600);
        List<UUID> made = tx.execute(s -> {
            find(id);
            long making = pictures.findByBannerIdOrderByCreatedAtDesc(id).stream()
                    .filter(p -> p.getStatus() == PromoBannerPicture.Status.MAKING).count();
            if (making > 0) throw new UnprocessableException("Pictures for this banner are still being made; wait for them first.");
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                PromoBannerPicture p = new PromoBannerPicture();
                p.setBannerId(id);
                p.setSource(PromoBannerPicture.Source.AI);
                p.setStatus(PromoBannerPicture.Status.MAKING);
                p.setBrief(brief);
                ids.add(pictures.save(p).getId());
            }
            return ids;
        });
        // after the rows are committed, so the maker's own transactions find them
        for (UUID pictureId : made) maker.make(pictureId);
        return get(id);
    }

    /** A picture staff uploaded (or pasted the address of). */
    public AdminView upload(UUID id, Upload in) {
        String url = in == null || in.imageUrl() == null ? "" : in.imageUrl().strip();
        if (!url.startsWith("https://")) throw new UnprocessableException("Upload a picture first.");
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            PromoBannerPicture p = ready(id, PromoBannerPicture.Source.UPLOAD, url, null);
            if (b.getImageUrl() == null) choose(b, p);
        });
        changed();
        return get(id);
    }

    /** The featured products' own main photos, as pictures to choose from. */
    public AdminView fromProducts(UUID id) {
        tx.executeWithoutResult(s -> {
            find(id);
            List<ProductCard> featured = products(List.of(id)).getOrDefault(id, List.of());
            if (featured.isEmpty()) throw new UnprocessableException("Add a featured product first.");
            Set<String> have = new HashSet<>();
            pictures.findByBannerIdOrderByCreatedAtDesc(id).forEach(p -> have.add(p.getImageUrl()));
            for (ProductCard c : featured) {
                if (c.imageUrl() != null && have.add(c.imageUrl())) {
                    ready(id, PromoBannerPicture.Source.PRODUCT, c.imageUrl(), c.name());
                }
            }
        });
        return get(id);
    }

    public AdminView choosePicture(UUID id, UUID pictureId) {
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            PromoBannerPicture p = pictures.findById(pictureId)
                    .filter(x -> x.getBannerId().equals(id))
                    .orElseThrow(() -> new NotFoundException("No such picture."));
            if (p.getStatus() != PromoBannerPicture.Status.READY) throw new UnprocessableException("That picture isn't ready.");
            choose(b, p);
        });
        changed();
        return get(id);
    }

    public AdminView deletePicture(UUID id, UUID pictureId) {
        tx.executeWithoutResult(s -> {
            PromoBanner b = find(id);
            PromoBannerPicture p = pictures.findById(pictureId)
                    .filter(x -> x.getBannerId().equals(id))
                    .orElseThrow(() -> new NotFoundException("No such picture."));
            if (p.getStatus() == PromoBannerPicture.Status.MAKING) throw new UnprocessableException("It is still being made.");
            if (Objects.equals(b.getImageUrl(), p.getImageUrl())) {
                throw new UnprocessableException("This is the banner's picture; choose another one first.");
            }
            pictures.delete(p);
        });
        return get(id);
    }

    private void choose(PromoBanner b, PromoBannerPicture p) {
        if (!Objects.equals(b.getImageUrl(), p.getImageUrl())) {
            b.setImageUrl(p.getImageUrl());
            b.setFocusX((short) 50);
            b.setFocusY((short) 50);
            backToDraft(b);
        }
    }

    private PromoBannerPicture ready(UUID bannerId, PromoBannerPicture.Source source, String url, String note) {
        PromoBannerPicture p = new PromoBannerPicture();
        p.setBannerId(bannerId);
        p.setSource(source);
        p.setStatus(PromoBannerPicture.Status.READY);
        p.setImageUrl(url);
        p.setNote(note);
        return pictures.save(p);
    }

    // ---------------------------------------------------------------- staff: words

    /**
     * Three sets of sales words for the banner as it is saved (owner, 2026-10-31), from its products, prices, offer and
     * occasion; {@code hint} is staff's optional idea. Nothing is saved: staff pick one into the form.
     */
    public List<WordSuggestion> suggestWords(UUID id, WordsRequest in) {
        if (text == null || !gemini.geminiConfigured()) {
            throw new UnprocessableException("Gemini is not set up, so words can't be suggested.");
        }
        String prompt = tx.execute(s -> {
            PromoBanner b = find(id);
            List<ProductCard> cards = products(List.of(id)).getOrDefault(id, List.of());
            List<String> lines = cards.stream().map(c -> c.name()
                    + (c.price() == null ? "" : " — KSh " + c.price().setScale(0, RoundingMode.HALF_UP).toPlainString())
                    + (c.dealPrice() == null ? "" : " (deal KSh " + c.dealPrice().setScale(0, RoundingMode.HALF_UP).toPlainString() + ")"))
                    .toList();
            String theme = b.getOccasionId() == null ? null
                    : occasions.findById(b.getOccasionId()).map(Occasion::getName).orElse(null);
            return PromoBannerPrompts.words(b.getPlacement(), lines, offer(b, cards), theme,
                    in == null ? null : in.hint());
        });
        com.fasterxml.jackson.databind.JsonNode answer;
        try {
            answer = text.ask(null, prompt, PromoBannerPrompts.wordsSchema());
        } catch (com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException e) {
            throw new UnprocessableException(e.isQuotaExceeded()
                    ? "Gemini's limit for now is used up. Try again later." : e.getMessage());
        }
        List<WordSuggestion> out = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode s : answer.path("suggestions")) {
            String headline = tidyWords(s.path("headline").asText(""), 80);
            if (headline == null) continue;
            out.add(new WordSuggestion(headline, tidyWords(s.path("subline").asText(""), 140),
                    tidyWords(s.path("cta").asText(""), 30)));
            if (out.size() == 3) break;
        }
        if (out.isEmpty()) throw new UnprocessableException("Gemini gave no usable words; try again.");
        return out;
    }

    /** One line, no quotation marks, cut at a word to fit the field. */
    static String tidyWords(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("[\"“”]", "").replaceAll("\\s+", " ").strip();
        if (t.length() > max) {
            int cut = t.lastIndexOf(' ', max);
            t = t.substring(0, cut > max / 2 ? cut : max).strip();
        }
        return t.isEmpty() ? null : t;
    }

    // ---------------------------------------------------------------- the shop

    /** The live banners of the new places (hero, strip, tile), in carousel order. */
    public List<PublicView> live() {
        return tx.execute(s -> {
            List<PromoBanner> on = banners.findLive(clock.instant()).stream()
                    .filter(b -> !b.getPlacement().isSmallSlot()).toList();
            return publicViews(on);
        });
    }

    /** The live banners of the V84 slots, for the shop's occasion view. */
    List<PublicView> liveSmallSlots() {
        List<PromoBanner> on = banners.findLive(clock.instant()).stream()
                .filter(b -> b.getPlacement().isSmallSlot()).toList();
        return publicViews(on);
    }

    private List<PublicView> publicViews(List<PromoBanner> on) {
        if (on.isEmpty()) return List.of();
        Context c = context();
        Map<UUID, List<ProductCard>> featured = products(on.stream().map(PromoBanner::getId).toList());
        return on.stream().map(b -> {
            List<ProductCard> cards = featured.getOrDefault(b.getId(), List.of());
            return new PublicView(b.getId(), b.getPlacement().name(), b.getCategoryId(),
                    c.slugs().get(b.getCategoryId()), link(b, c), b.getHeadline(), b.getSubline(), b.getCtaLabel(),
                    offer(b, cards), countdown(b, cards), theme(b, c), b.getTextTone(), b.getImageUrl(), b.getFocusX(),
                    b.getFocusY(), cards, readStyle(b.getStyle()));
        }).toList();
    }

    // ---------------------------------------------------------------- reading

    /** What the views need besides the banner rows: category names and slugs, occasions, product names. */
    private record Context(Map<UUID, String> categoryNames, Map<UUID, String> slugs, Map<UUID, Occasion> occasions,
                           Instant now) {}

    @SuppressWarnings("unchecked")
    private Context context() {
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, String> slugs = new HashMap<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery(
                "SELECT CAST(id AS VARCHAR), name, slug FROM category").getResultList()) {
            UUID id = UUID.fromString((String) row[0]);
            names.put(id, (String) row[1]);
            slugs.put(id, (String) row[2]);
        }
        Map<UUID, Occasion> byId = new HashMap<>();
        occasions.findAll().forEach(o -> byId.put(o.getId(), o));
        return new Context(names, slugs, byId, clock.instant());
    }

    private AdminView admin(PromoBanner b, Context c) {
        List<ProductCard> cards = products(List.of(b.getId())).getOrDefault(b.getId(), List.of());
        List<PictureView> pics = pictures.findByBannerIdOrderByCreatedAtDesc(b.getId()).stream()
                .map(p -> new PictureView(p.getId(), p.getSource().name(), p.getStatus().name(), p.getImageUrl(),
                        p.getBrief(), p.getNote(), p.getCreatedAt()))
                .toList();
        return new AdminView(b.getId(), b.getName(), b.getPlacement().name(), b.getCategoryId(),
                c.categoryNames().get(b.getCategoryId()), link(b, c), b.getHeadline(), b.getSubline(), b.getCtaLabel(),
                b.getOfferMode().name(), b.getOfferText(), offer(b, cards), b.isShowCountdown(), countdown(b, cards),
                theme(b, c), b.getTextTone(), b.getImageUrl(), b.getFocusX(), b.getFocusY(), b.getStartsAt(),
                b.getEndsAt(), b.getSortOrder(), b.getStatus().name(), state(b, c.now()), cards, pics, missing(b, cards),
                b.getCreatedBy(), b.getApprovedBy(), b.getApprovedAt(), b.getUpdatedAt(), readStyle(b.getStyle()));
    }

    static String state(PromoBanner b, Instant now) {
        return switch (b.getStatus()) {
            case DRAFT -> "DRAFT";
            case PAUSED -> "PAUSED";
            case APPROVED -> b.getEndsAt() != null && !b.getEndsAt().isAfter(now) ? "ENDED"
                    : b.getStartsAt() != null && b.getStartsAt().isAfter(now) ? "SCHEDULED" : "LIVE";
        };
    }

    /** What a customer would miss, in staff's words; empty when it can be approved. */
    static List<String> missing(PromoBanner b, List<ProductCard> products) {
        List<String> out = new ArrayList<>();
        if (b.getImageUrl() == null) out.add("choose a picture");
        boolean circle = b.getPlacement() == PromoBanner.Placement.CIRCLE;
        if (circle && b.getCategoryId() == null) out.add("choose whose circle it is");
        if (!circle && (b.getHeadline() == null || b.getHeadline().isBlank())) out.add("write a headline");
        if (b.getLinkType() == PromoBanner.LinkType.CATEGORY && b.getLinkCategoryId() == null) out.add("choose the category it opens");
        if (b.getLinkType() == PromoBanner.LinkType.PRODUCT && b.getLinkVariantId() == null) out.add("choose the product it opens");
        if (b.getOfferMode() == PromoBanner.OfferMode.CUSTOM && (b.getOfferText() == null || b.getOfferText().isBlank())) {
            out.add("write the offer, or set the offer to automatic or none");
        }
        if (b.getStartsAt() == null || b.getEndsAt() == null) out.add("give it a start and an end");
        else if (!b.getEndsAt().isAfter(b.getStartsAt())) out.add("make it end after it starts");
        if (b.getPlacement() == PromoBanner.Placement.HERO && products.isEmpty() && b.getOfferMode() == PromoBanner.OfferMode.AUTO) {
            out.add("add a featured product (or set the offer to your own words or none)");
        }
        return out;
    }

    /** "Up to 25% off" from the featured products' live deals (automatic), staff's words, or nothing. */
    static String offer(PromoBanner b, List<ProductCard> products) {
        return switch (b.getOfferMode()) {
            case NONE -> null;
            case CUSTOM -> b.getOfferText();
            case AUTO -> {
                int best = 0;
                Instant now = Instant.now();
                for (ProductCard p : products) {
                    if (p.dealPrice() == null || p.price() == null || p.price().signum() <= 0) continue;
                    if (p.dealEndsAt() != null && !p.dealEndsAt().isAfter(now)) continue;
                    int pct = p.price().subtract(p.dealPrice()).multiply(BigDecimal.valueOf(100))
                            .divide(p.price(), 0, RoundingMode.DOWN).intValue();
                    best = Math.max(best, pct);
                }
                yield best >= 5 ? "Up to " + best + "% off" : null;
            }
        };
    }

    /** When the countdown runs to: the soonest featured deal's end, else the banner's own end. */
    static Instant countdown(PromoBanner b, List<ProductCard> products) {
        if (!b.isShowCountdown()) return null;
        Instant now = Instant.now();
        return products.stream().map(ProductCard::dealEndsAt).filter(Objects::nonNull).filter(t -> t.isAfter(now))
                .min(Comparator.naturalOrder()).orElse(b.getEndsAt());
    }

    private Theme theme(PromoBanner b, Context c) {
        Occasion o = b.getOccasionId() == null ? null : c.occasions().get(b.getOccasionId());
        if (o == null) return new Theme(null, "Trendysh", SHOP_MAIN, SHOP_LIGHT);
        String[] colors = o.getColors().split(",");
        return new Theme(o.getId(), o.getName(), colors[0].strip(), colors.length > 1 ? colors[1].strip() : "#FFFFFF");
    }

    @SuppressWarnings("unchecked")
    private Link link(PromoBanner b, Context c) {
        return switch (b.getLinkType()) {
            case DEALS -> new Link("DEALS", null, null, null, null, null);
            case CATEGORY -> new Link("CATEGORY", b.getLinkCategoryId(), c.slugs().get(b.getLinkCategoryId()),
                    c.categoryNames().get(b.getLinkCategoryId()), null, null);
            case PRODUCT -> {
                String name = b.getLinkVariantId() == null ? null : (String) ((List<Object>) em.createNativeQuery(
                                "SELECT p.name FROM product_variant v JOIN product p ON p.id = v.product_id WHERE v.id = :v")
                        .setParameter("v", b.getLinkVariantId()).getResultList()).stream().findFirst().orElse(null);
                yield new Link("PRODUCT", null, null, null, b.getLinkVariantId(), name);
            }
        };
    }

    /** Each banner's featured products in order, with name, main photo, price and live deal. */
    @SuppressWarnings("unchecked")
    Map<UUID, List<ProductCard>> products(List<UUID> bannerIds) {
        Map<UUID, List<ProductCard>> out = new HashMap<>();
        if (bannerIds.isEmpty()) return out;
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                SELECT CAST(bp.banner_id AS VARCHAR), CAST(v.id AS VARCHAR),
                       p.name || COALESCE(' · ' || NULLIF(v.option_label, ''), ''),
                       (SELECT i.url FROM product_image i
                         WHERE i.variant_id = v.id OR (i.variant_id IS NULL AND i.product_id = v.product_id)
                         ORDER BY (i.variant_id IS NULL), i.is_primary DESC, i.sort_order LIMIT 1),
                       v.selling_price,
                       CASE WHEN v.deal_ends_at > now() THEN v.deal_price END,
                       CASE WHEN v.deal_ends_at > now() THEN v.deal_ends_at END
                FROM promo_banner_product bp
                JOIN product_variant v ON v.id = bp.variant_id
                JOIN product p ON p.id = v.product_id
                WHERE bp.banner_id IN (:ids)
                ORDER BY bp.position
                """).setParameter("ids", bannerIds).getResultList()) {
            out.computeIfAbsent(UUID.fromString((String) row[0]), k -> new ArrayList<>()).add(new ProductCard(
                    UUID.fromString((String) row[1]), (String) row[2], (String) row[3], (BigDecimal) row[4],
                    (BigDecimal) row[5], row[6] == null ? null : toInstant(row[6])));
        }
        return out;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        return Instant.parse(value.toString());
    }

    private void setProducts(UUID bannerId, List<UUID> variantIds) {
        List<UUID> unique = variantIds.stream().filter(Objects::nonNull).distinct().toList();
        if (unique.size() > MAX_PRODUCTS) throw new UnprocessableException("Feature at most " + MAX_PRODUCTS + " products.");
        em.createNativeQuery("DELETE FROM promo_banner_product WHERE banner_id = :b").setParameter("b", bannerId).executeUpdate();
        for (int i = 0; i < unique.size(); i++) {
            em.createNativeQuery("INSERT INTO promo_banner_product (banner_id, variant_id, position) VALUES (:b, :v, :p)")
                    .setParameter("b", bannerId).setParameter("v", unique.get(i)).setParameter("p", i).executeUpdate();
        }
    }

    // ---------------------------------------------------------------- helpers

    private PromoBanner find(UUID id) {
        return banners.findById(id).orElseThrow(() -> new NotFoundException("No such banner."));
    }

    /** A change to what customers would see needs approving again. */
    private static void backToDraft(PromoBanner b) {
        if (b.getStatus() != PromoBanner.Status.DRAFT) {
            b.setStatus(PromoBanner.Status.DRAFT);
            b.setApprovedBy(null);
        }
    }

    private static PromoBanner.Placement placement(String raw) {
        return enumOf(PromoBanner.Placement.class, raw, "Choose where the banner shows.");
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, String message) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException(message);
        }
    }

    private static String defaultName(PromoBanner.Placement p) {
        return switch (p) {
            case HERO -> "Home banner";
            case STRIP -> "Campaign strip";
            case TILE -> "Category tile";
            case OFFER_CARD -> "Offers-row card";
            case GRID_TILE -> "Grid tile";
            case CIRCLE -> "Category circle";
        };
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String optional(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        if (t.length() > max) throw new UnprocessableException("Keep it to " + max + " characters or fewer: \"" + t + "\"");
        return t;
    }

    private static String required(String s, int max, String message) {
        String t = optional(s, max);
        if (t == null) throw new UnprocessableException(message);
        return t;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper STYLE_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static String writeStyle(Style style) {
        if (style == null) return null;
        try {
            return STYLE_JSON.writeValueAsString(style);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A saved look the shop can't read (hand-edited, say) falls back to the automatic one rather than failing. */
    static Style readStyle(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return STYLE_JSON.readValue(json, Style.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private void changed() {
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
    }
}
