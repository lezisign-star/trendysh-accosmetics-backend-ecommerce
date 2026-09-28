package com.nbh.backend.tumaboda;

import com.nbh.backend.cart.service.CartService;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceDetails;
import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceSuggestion;
import com.nbh.backend.tumaboda.TumaBodaDtos.Quote;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the checkout's TumaBoda delivery step needs before an order exists: whether TumaBoda is on,
 * address suggestions, a suggestion's coordinates, and a price for a drop-off point. Public (a guest is
 * checking out), so every call is rate-limited by {@link TumaBodaThrottle}, and the answers are shaped so
 * that nothing about TumaBoda's side (key, error text, response bodies) reaches the browser.
 *
 * <p>The quote is a preview only: checkout asks TumaBoda again, server-side, and charges that price —
 * never anything the browser sends.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public/tumaboda")
public class TumaBodaPublicController {

    private static final String SESSION_HEADER = "X-Cart-Session";
    private static final int MIN_SEARCH_LENGTH = 3;
    private static final int MAX_SEARCH_LENGTH = 120;
    private static final Pattern PLACE_ID = Pattern.compile("[A-Za-z0-9_\\-]{5,300}");

    public record QuoteRequest(@NotNull BigDecimal lat, @NotNull BigDecimal lng) {}

    /** {@code feeKes} and the rest are null unless {@code available}; {@code message} says what to do instead. */
    public record QuoteResponse(boolean available, BigDecimal feeKes, BigDecimal distanceKm, Integer durationMinutes,
                                String message) {
        static QuoteResponse unavailable(String message) {
            return new QuoteResponse(false, null, null, null, message);
        }
    }

    private final TumaBodaConfig config;
    private final TumaBodaClient client;
    private final TumaBodaQuoteService quotes;
    private final TumaBodaThrottle throttle;
    private final CartService cartService;

    public TumaBodaPublicController(TumaBodaConfig config, TumaBodaClient client, TumaBodaQuoteService quotes,
                                    TumaBodaThrottle throttle, CartService cartService) {
        this.config = config;
        this.client = client;
        this.quotes = quotes;
        this.throttle = throttle;
        this.cartService = cartService;
    }

    /** Lets the checkout decide whether to offer an address search at all. */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", config.enabled());
    }

    @GetMapping("/maps/autocomplete")
    public List<PlaceSuggestion> autocomplete(@RequestParam String input, HttpServletRequest request) {
        String text = input.trim();
        if (!config.enabled() || text.length() < MIN_SEARCH_LENGTH) {
            return List.of();
        }
        if (text.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("That search is too long");
        }
        throttle.lookup(caller(request));
        try {
            return client.autocomplete(text);
        } catch (TumaBodaException e) {
            throw unavailable("address search", e);
        }
    }

    @GetMapping("/maps/place-details")
    public PlaceDetails placeDetails(@RequestParam String placeId, HttpServletRequest request) {
        if (!PLACE_ID.matcher(placeId).matches()) {
            throw new IllegalArgumentException("That isn't a valid place");
        }
        if (!config.enabled()) {
            throw new EntityNotFoundException("Address lookup isn't available right now");
        }
        throttle.lookup(caller(request));
        PlaceDetails details;
        try {
            details = client.placeDetails(placeId).orElse(null);
        } catch (TumaBodaException e) {
            throw unavailable("place lookup", e);
        }
        if (details == null) {
            throw new EntityNotFoundException("Couldn't find that place on the map");
        }
        if (!TumaBodaQuoteService.isPlausibleKenyanPoint(details.latitude(), details.longitude())) {
            throw new UnprocessableException("That place isn't in Kenya");
        }
        return details;
    }

    @PostMapping("/quote")
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest body,
                               @RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                               HttpServletRequest request) {
        if (!quotes.enabled()) {
            return QuoteResponse.unavailable(
                    "TumaBoda delivery isn't available right now. Choose matatu or courier, or collect from the shop.");
        }
        throttle.quote(caller(request));
        try {
            Quote quote = quotes.quote(body.lat(), body.lng(), cartService.basketValue(sessionId));
            return new QuoteResponse(true, quote.totalKes(), quote.distanceKm(), quote.durationMinutes(), null);
        } catch (TumaBodaException e) {
            log.warn("TumaBoda quote failed ({}): {}", e.errorCode(), e.getMessage());
            if ("BAD_LOCATION".equals(e.errorCode())) {
                return QuoteResponse.unavailable("That doesn't look like an address in Kenya. Please pick another.");
            }
            return QuoteResponse.unavailable(e.retryable()
                    ? "We couldn't get a TumaBoda price just now. Please try again in a minute, or choose another option."
                    : "TumaBoda can't deliver to that address. Please pick another, or choose matatu or courier.");
        }
    }

    // ---- helpers ----

    /** Nothing about TumaBoda's own failure text is passed on: it can carry request details we don't want public. */
    private static TumaBodaException unavailable(String what, TumaBodaException cause) {
        log.warn("TumaBoda {} failed ({}): {}", what, cause.errorCode(), cause.getMessage());
        return new TumaBodaException("UNAVAILABLE", "TumaBoda's " + what + " isn't available right now", true);
    }

    /**
     * Who is asking, for rate limiting. Behind the host's proxy the connection's own address is the
     * proxy's, so the client is the LAST entry of X-Forwarded-For — the one that proxy itself appended.
     * Earlier entries are whatever the caller chose to send, so trusting them would let anyone dodge the
     * limit by inventing a new address per request.
     */
    static String caller(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String last = hops[hops.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
