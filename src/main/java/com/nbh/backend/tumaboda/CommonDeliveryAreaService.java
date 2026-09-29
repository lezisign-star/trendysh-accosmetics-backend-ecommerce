package com.nbh.backend.tumaboda;

import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceDetails;
import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceSuggestion;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Fuzzy fallback search over the seeded {@link CommonDeliveryArea} list (see its javadoc for why it
 * exists), the same scoring Moments Packaging uses: substring/prefix/token-overlap plus a little
 * edit-distance tolerance for typos. Deliberately generous — the goal is "the customer almost always
 * sees something to pick," not precision ranking; TumaBoda's own live search is tried first in {@link
 * TumaBodaPublicController} and is the accurate source when it works.
 *
 * <p>A seeded area is exposed through the same {@link PlaceSuggestion}/{@link PlaceDetails} shapes
 * TumaBoda's own places use, under a synthetic {@code area:<uuid>} id ({@link #AREA_PREFIX}) — so the
 * checkout picker's existing "pick a suggestion, then resolve it" flow needs no special case at all for
 * where a suggestion came from.
 */
@Service
public class CommonDeliveryAreaService {

    static final String AREA_PREFIX = "area:";
    private static final int MAX_RESULTS = 8;
    private static final int MIN_SCORE = 30;
    /** Beyond this, a raw GPS point isn't close enough to any seeded area to trust as its label. */
    private static final double MAX_NEAREST_KM = 15.0;

    // Hand-picked, not data-driven — CommonDeliveryArea has no popularity signal to rank on. Shown as
    // quick-pick chips in the address picker so a customer isn't forced to type for well-known areas.
    private static final List<String> QUICK_PICK_NAMES = List.of(
            "Kilimani", "Westlands", "Karen", "Lavington", "Upper Hill", "South B");

    private final CommonDeliveryAreaRepository repository;

    public CommonDeliveryAreaService(CommonDeliveryAreaRepository repository) {
        this.repository = repository;
    }

    public boolean isAreaId(String placeId) {
        return placeId != null && placeId.startsWith(AREA_PREFIX);
    }

    /** Curated subset of {@link #allAreas()} for the picker's quick-pick chips — see {@link #QUICK_PICK_NAMES}. */
    public List<PlaceSuggestion> quickPicks() {
        List<CommonDeliveryArea> areas = activeAreas();
        return QUICK_PICK_NAMES.stream()
                .flatMap(name -> areas.stream().filter(a -> a.getName().equalsIgnoreCase(name)).limit(1))
                .map(this::toSuggestion)
                .toList();
    }

    /** Every seeded area, sorted by county then name, for a "more options" expansion. */
    public List<PlaceSuggestion> allAreas() {
        return activeAreas().stream()
                .sorted(Comparator.comparing((CommonDeliveryArea a) -> a.getCounty() == null ? "" : a.getCounty())
                        .thenComparing(CommonDeliveryArea::getName))
                .map(this::toSuggestion)
                .toList();
    }

    public List<PlaceSuggestion> fuzzySearch(String rawInput) {
        String query = normalize(rawInput);
        if (query.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = activeAreas().stream()
                .map(a -> new Scored(a, score(query, normalize(a.getName()))))
                .sorted(Comparator.comparingInt((Scored s) -> s.score).reversed())
                .toList();

        // Prefer a real, reasonably confident match; if nothing clears MIN_SCORE, still surface the
        // closest few areas with any positive score rather than a stone-wall-empty result — a query
        // with zero token overlap against every seeded area legitimately returns nothing.
        List<Scored> withMinScore = scored.stream().filter(s -> s.score >= MIN_SCORE).toList();
        List<Scored> results = !withMinScore.isEmpty() ? withMinScore : scored.stream().filter(s -> s.score > 0).toList();

        return results.stream().limit(MAX_RESULTS).map(s -> toSuggestion(s.area)).toList();
    }

    /** Resolves a synthetic {@code area:<uuid>} id back to coordinates; empty for anything else (a real TumaBoda placeId). */
    public Optional<PlaceDetails> resolve(String placeId) {
        if (!isAreaId(placeId)) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(placeId.substring(AREA_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return repository.findByIdAndActiveTrue(id).map(a -> new PlaceDetails(placeId,
                BigDecimal.valueOf(a.getLatitude()), BigDecimal.valueOf(a.getLongitude()),
                a.getName() + (a.getCounty() != null ? ", " + a.getCounty() : ""), a.getName()));
    }

    /**
     * A label for a raw GPS point when TumaBoda's own reverse-geocode is unavailable or off — the
     * nearest seeded area's name, only if it is close enough to actually mean something (see {@link
     * #MAX_NEAREST_KM}). The point itself (never snapped to the area's own coordinate) is still what
     * gets quoted and booked; this only supplies a human-readable label for it.
     */
    public Optional<String> nearestAreaLabel(BigDecimal lat, BigDecimal lng) {
        double pointLat = lat.doubleValue();
        double pointLng = lng.doubleValue();
        CommonDeliveryArea nearest = null;
        double nearestKm = Double.MAX_VALUE;
        for (CommonDeliveryArea area : activeAreas()) {
            double km = haversineKm(pointLat, pointLng, area.getLatitude(), area.getLongitude());
            if (km < nearestKm) {
                nearestKm = km;
                nearest = area;
            }
        }
        if (nearest == null || nearestKm > MAX_NEAREST_KM) {
            return Optional.empty();
        }
        String county = nearest.getCounty();
        return Optional.of("Near " + nearest.getName() + (county != null ? ", " + county : ""));
    }

    private List<CommonDeliveryArea> activeAreas() {
        return repository.findAllByActiveTrue();
    }

    private PlaceSuggestion toSuggestion(CommonDeliveryArea area) {
        String description = area.getName() + (area.getCounty() != null ? ", " + area.getCounty() : "");
        return new PlaceSuggestion(AREA_PREFIX + area.getId(), description);
    }

    private record Scored(CommonDeliveryArea area, int score) {}

    /** Higher is better; 0 means "no reasonable match". */
    private static int score(String query, String candidate) {
        if (candidate.isEmpty()) {
            return 0;
        }
        if (candidate.equals(query)) {
            return 100;
        }
        if (candidate.startsWith(query)) {
            return 90;
        }
        if (candidate.contains(query)) {
            return 80;
        }
        if (query.contains(candidate)) {
            return 75;
        }

        String[] queryTokens = query.split(" ");
        String[] candidateTokens = candidate.split(" ");
        int best = 0;
        for (String qt : queryTokens) {
            if (qt.length() < 2) {
                continue;
            }
            for (String ct : candidateTokens) {
                // Same length floor as above — without it a one-letter candidate token (e.g. "C" in
                // "South C") would match qt.startsWith(ct) against any query word starting with that
                // letter, a false positive Moments found live ("center" wrongly matching "South C").
                if (ct.length() < 2) {
                    continue;
                }
                if (ct.startsWith(qt) || qt.startsWith(ct)) {
                    best = Math.max(best, 60);
                    continue;
                }
                int dist = levenshtein(qt, ct);
                int maxLen = Math.max(qt.length(), ct.length());
                if (maxLen >= 3 && dist <= 1) {
                    best = Math.max(best, 50 - dist * 5);
                } else if (maxLen >= 6 && dist <= 2) {
                    best = Math.max(best, 45 - dist * 5);
                }
            }
        }
        return best;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }
}
