package com.nbh.backend.delivery;

import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.TooManyRequestsException;
import com.nbh.backend.delivery.DeliveryDtos.PlaceDto;
import com.nbh.backend.delivery.DeliveryDtos.PriceRequest;
import com.nbh.backend.delivery.DeliveryDtos.ResolveRequest;
import com.nbh.backend.delivery.DeliveryDtos.SavePlaceRequest;
import com.nbh.backend.delivery.DeliveryDtos.SuggestRequest;
import com.nbh.backend.delivery.DeliveryDtos.SuggestionDto;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff-managed delivery places with a price range per way of getting there (Metro, physically booked boda,
 * matatu), the customer-facing comparison, and the queue of places customers ask us to add. Every change
 * publishes a {@link DataChangedEvent} so open storefronts and dashboards refresh themselves.
 */
@Service
public class DeliveryPlaceService {

    private static final int SUGGESTIONS_PER_CLIENT_PER_HOUR = 5;

    private final DeliveryPlaceRepository places;
    private final DeliveryPlaceSuggestionRepository suggestions;
    private final ApplicationEventPublisher events;
    private final Map<String, List<Instant>> recentByClient = new ConcurrentHashMap<>();

    public DeliveryPlaceService(DeliveryPlaceRepository places, DeliveryPlaceSuggestionRepository suggestions,
                                ApplicationEventPublisher events) {
        this.places = places;
        this.suggestions = suggestions;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<PlaceDto> search(String q, boolean activeOnly) {
        String term = q == null || q.isBlank() ? null : q.trim();
        return places.search(term, activeOnly).stream().map(PlaceDto::from).toList();
    }

    @Transactional
    public PlaceDto create(SavePlaceRequest request) {
        if (places.existsByNameIgnoreCase(request.name().trim())) {
            throw new ConflictException("A place called \"" + request.name().trim() + "\" already exists");
        }
        DeliveryPlace place = new DeliveryPlace();
        apply(place, request);
        PlaceDto dto = PlaceDto.from(places.saveAndFlush(place));
        changed();
        return dto;
    }

    @Transactional
    public PlaceDto update(UUID id, SavePlaceRequest request) {
        DeliveryPlace place = places.findWithPricesById(id)
                .orElseThrow(() -> new NotFoundException("No delivery place: " + id));
        String name = request.name().trim();
        if (!place.getName().equalsIgnoreCase(name) && places.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A place called \"" + name + "\" already exists");
        }
        apply(place, request);
        PlaceDto dto = PlaceDto.from(places.saveAndFlush(place));
        changed();
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        DeliveryPlace place = places.findById(id).orElseThrow(() -> new NotFoundException("No delivery place: " + id));
        places.delete(place);
        changed();
    }

    private void apply(DeliveryPlace place, SavePlaceRequest request) {
        place.setName(request.name().trim());
        place.setRegion(blankToNull(request.region()));
        place.setNotes(blankToNull(request.notes()));
        place.setActive(request.active());
        EnumSet<DeliveryMethod> seen = EnumSet.noneOf(DeliveryMethod.class);
        List<DeliveryPlacePrice> next = new ArrayList<>();
        for (PriceRequest r : request.prices()) {
            if (!seen.add(r.method())) {
                throw new ConflictException("Each way of getting there can only be listed once per place");
            }
            if (r.priceMax().compareTo(r.priceMin()) < 0) {
                throw new ConflictException(r.method() + ": the highest price can't be below the lowest");
            }
            DeliveryPlacePrice price = place.getPrices().stream()
                    .filter(p -> p.getMethod() == r.method()).findFirst().orElseGet(DeliveryPlacePrice::new);
            price.setPlace(place);
            price.setMethod(r.method());
            price.setPriceMin(r.priceMin());
            price.setPriceMax(r.priceMax());
            price.setEtaText(blankToNull(r.etaText()));
            price.setNotes(blankToNull(r.notes()));
            next.add(price);
        }
        place.getPrices().clear();
        place.getPrices().addAll(next);
    }

    // ---- customer suggestions ----

    @Transactional
    public void suggest(SuggestRequest request, String clientKey) {
        throttle(clientKey);
        DeliveryPlaceSuggestion s = new DeliveryPlaceSuggestion();
        s.setName(request.name().trim());
        s.setDetails(blankToNull(request.details()));
        s.setContact(blankToNull(request.contact()));
        suggestions.save(s);
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.DELIVERY_SUGGESTIONS));
    }

    @Transactional(readOnly = true)
    public List<SuggestionDto> pendingSuggestions() {
        return suggestions.findByStatusOrderByCreatedAtAsc(DeliveryPlaceSuggestion.Status.PENDING)
                .stream().map(SuggestionDto::from).toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return suggestions.countByStatus(DeliveryPlaceSuggestion.Status.PENDING);
    }

    /** Marks a suggestion approved (optionally pointing at the place it became) or rejected. */
    @Transactional
    public SuggestionDto resolve(UUID id, boolean approve, ResolveRequest request) {
        DeliveryPlaceSuggestion s = suggestions.findById(id)
                .orElseThrow(() -> new NotFoundException("No suggestion: " + id));
        if (s.getStatus() != DeliveryPlaceSuggestion.Status.PENDING) {
            throw new ConflictException("This suggestion was already handled");
        }
        s.setStatus(approve ? DeliveryPlaceSuggestion.Status.APPROVED : DeliveryPlaceSuggestion.Status.REJECTED);
        s.setResolvedNote(request == null ? null : blankToNull(request.note()));
        s.setPlaceId(request == null ? null : request.placeId());
        SuggestionDto dto = SuggestionDto.from(suggestions.save(s));
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.DELIVERY_SUGGESTIONS));
        return dto;
    }

    private void changed() {
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.DELIVERY_PLACES));
    }

    private void throttle(String clientKey) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        List<Instant> times = recentByClient.computeIfAbsent(clientKey, k -> new ArrayList<>());
        synchronized (times) {
            times.removeIf(t -> t.isBefore(cutoff));
            if (times.size() >= SUGGESTIONS_PER_CLIENT_PER_HOUR) {
                throw new TooManyRequestsException("You've sent several suggestions already. Please try again later.");
            }
            times.add(Instant.now());
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
