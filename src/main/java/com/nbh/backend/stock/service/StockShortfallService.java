package com.nbh.backend.stock.service;

import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.stock.entity.StockShortfallEvent;
import com.nbh.backend.stock.repository.StockShortfallEventRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "A customer could not order" (owner, 2026-10-28): the shop writes down every shopper it refuses for stock, and staff
 * see each one as a critical alert until a person marks it handled. Several at once are one line on the staff screens.
 */
@Service
public class StockShortfallService {

    /** The same shopper refused for the same option again within this long is the same event. */
    static final Duration SAME_SHOPPER_WINDOW = Duration.ofMinutes(15);
    /** How many unhandled events a screen is told about by name; the count covers all of them. */
    static final int LISTED = 20;

    public record Shortfall(UUID id, UUID variantId, String productName, BigDecimal qtyAsked, BigDecimal qtyLeft,
                            String source, String contactName, String contactPhone, String contactEmail,
                            Instant createdAt) {
        static Shortfall from(StockShortfallEvent e) {
            return new Shortfall(e.getId(), e.getVariantId(), e.getProductName(), e.getQtyAsked(), e.getQtyLeft(),
                    e.getSource().name(), e.getContactName(), e.getContactPhone(), e.getContactEmail(), e.getCreatedAt());
        }
    }

    /**
     * @param upTo handed back to {@link #handleAll} so an event arriving while someone reads the list is not cleared by
     *             their tap
     */
    public record Unhandled(long count, Instant upTo, List<Shortfall> events) {}

    /** One option shoppers were refused for lately, for the Restock screen: how often, how many, and who. */
    public record RefusedOption(UUID variantId, String productName, int times, BigDecimal qtyAsked, Instant lastAt,
                                List<Shortfall> events) {}

    private final StockShortfallEventRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StockShortfallService(StockShortfallEventRepository repository, ApplicationEventPublisher events,
                                 Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Writes the refusal down in its own transaction: the shopper's request is being refused (and rolled back) as this
     * runs, and the record must survive that. Callers swallow any failure here, so it can never change what the shopper
     * is told.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID variantId, String productName, BigDecimal qtyAsked, BigDecimal qtyLeft,
                       StockShortfallEvent.Source source, String sessionRef,
                       String contactName, String contactPhone, String contactEmail) {
        Instant now = Instant.now(clock);
        if (sessionRef != null
                && repository.existsByVariantIdAndSessionRefAndCreatedAtAfter(variantId, sessionRef,
                now.minus(SAME_SHOPPER_WINDOW))) {
            return;
        }
        StockShortfallEvent e = new StockShortfallEvent();
        e.setVariantId(variantId);
        e.setProductName(cut(productName, 300));
        e.setQtyAsked(qtyAsked);
        e.setQtyLeft(qtyLeft == null || qtyLeft.signum() < 0 ? BigDecimal.ZERO : qtyLeft);
        e.setSource(source);
        e.setSessionRef(cut(sessionRef, 100));
        e.setContactName(cut(contactName, 200));
        e.setContactPhone(cut(contactPhone, 30));
        e.setContactEmail(cut(contactEmail, 200));
        repository.save(e);
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CRITICAL_ALERTS));
    }

    @Transactional(readOnly = true)
    public Unhandled unhandled() {
        long count = repository.countByHandledAtIsNull();
        if (count == 0) return new Unhandled(0, null, List.of());
        Instant upTo = Instant.now(clock);
        return new Unhandled(count, upTo,
                repository.unhandled(PageRequest.of(0, LISTED)).stream().map(Shortfall::from).toList());
    }

    @Transactional
    public void handle(UUID id, String staffEmail) {
        StockShortfallEvent e = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("No such event: " + id));
        if (e.getHandledAt() == null) {
            e.setHandledAt(Instant.now(clock));
            e.setHandledBy(staffEmail);
            events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CRITICAL_ALERTS));
        }
    }

    /** Marks handled every event that arrived up to {@code upTo} (now, when none is given). */
    @Transactional
    public int handleAll(Instant upTo, String staffEmail) {
        Instant now = Instant.now(clock);
        Instant limit = upTo == null || upTo.isAfter(now) ? now : upTo;
        int handled = repository.handleAllUpTo(limit, now, staffEmail);
        if (handled > 0) events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CRITICAL_ALERTS));
        return handled;
    }

    /** Options shoppers were refused for in the last {@code days} days, most refused first. */
    @Transactional(readOnly = true)
    public List<RefusedOption> refusedLately(int days) {
        Instant after = Instant.now(clock).minus(Duration.ofDays(Math.max(1, Math.min(days, 90))));
        List<UUID> variants = repository.variantsRefusedSince(after);
        if (variants.isEmpty()) return List.of();
        Map<UUID, List<StockShortfallEvent>> byVariant = new LinkedHashMap<>();
        for (StockShortfallEvent e : repository.findByVariantIdInAndCreatedAtAfterOrderByCreatedAtDesc(variants, after)) {
            byVariant.computeIfAbsent(e.getVariantId(), k -> new ArrayList<>()).add(e);
        }
        List<RefusedOption> out = new ArrayList<>();
        byVariant.forEach((variantId, list) -> out.add(new RefusedOption(variantId, list.get(0).getProductName(),
                list.size(), list.stream().map(StockShortfallEvent::getQtyAsked).reduce(BigDecimal.ZERO, BigDecimal::add),
                list.get(0).getCreatedAt(), list.stream().map(Shortfall::from).toList())));
        out.sort(Comparator.comparingInt(RefusedOption::times).reversed()
                .thenComparing(RefusedOption::lastAt, Comparator.reverseOrder()));
        return out;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
