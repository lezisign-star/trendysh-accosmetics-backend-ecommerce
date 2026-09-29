package com.nbh.backend.tumaboda;

import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceDetails;
import com.nbh.backend.tumaboda.TumaBodaDtos.PlaceSuggestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fuzzy scoring, the synthetic {@code area:<uuid>} id round trip, and the "nearest area" reverse-geocode
 * fallback — everything {@link CommonDeliveryAreaService} does on its own, without a real database (a plain
 * mocked repository is enough since the service's own logic is what is under test here).
 */
class CommonDeliveryAreaServiceTest {

    private final CommonDeliveryAreaRepository repository = mock(CommonDeliveryAreaRepository.class);
    private final CommonDeliveryAreaService service = new CommonDeliveryAreaService(repository);

    private static CommonDeliveryArea area(String name, String county, double lat, double lng) {
        CommonDeliveryArea a = new CommonDeliveryArea();
        a.setId(UUID.randomUUID());
        a.setName(name);
        a.setCounty(county);
        a.setLatitude(lat);
        a.setLongitude(lng);
        a.setActive(true);
        return a;
    }

    @Test
    void anExactOrPrefixMatchRanksAboveAWeakerOne() {
        CommonDeliveryArea kilimani = area("Kilimani", "Nairobi", -1.2921, 36.7820);
        CommonDeliveryArea kileleshwa = area("Kileleshwa", "Nairobi", -1.2793, 36.7793);
        when(repository.findAllByActiveTrue()).thenReturn(List.of(kileleshwa, kilimani));

        List<PlaceSuggestion> results = service.fuzzySearch("kilim");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).description()).startsWith("Kilimani");
    }

    @Test
    void aSmallTypoStillFindsTheRightArea() {
        CommonDeliveryArea westlands = area("Westlands", "Nairobi", -1.2673, 36.8062);
        when(repository.findAllByActiveTrue()).thenReturn(List.of(westlands));

        // one substituted letter
        assertThat(service.fuzzySearch("westlnds")).extracting(PlaceSuggestion::description)
                .anyMatch(d -> d.startsWith("Westlands"));
    }

    @Test
    void aOneLetterCandidateTokenDoesNotFalselyMatchAnUnrelatedWord() {
        // Regression for the bug Moments found live: "South C" wrongly matching "center"-type queries
        // via a bare "C" token doing qt.startsWith(ct) against anything starting with C.
        CommonDeliveryArea southC = area("South C", "Nairobi", -1.3200, 36.8180);
        when(repository.findAllByActiveTrue()).thenReturn(List.of(southC));

        assertThat(service.fuzzySearch("city center")).extracting(PlaceSuggestion::description)
                .noneMatch(d -> d.startsWith("South C"));
    }

    @Test
    void aQueryThatMatchesNothingReturnsNothing() {
        when(repository.findAllByActiveTrue()).thenReturn(List.of(area("Karen", "Nairobi", -1.3197, 36.7076)));

        assertThat(service.fuzzySearch("zzzzzzzzzz")).isEmpty();
    }

    @Test
    void blankInputReturnsNothingWithoutTouchingTheRepository() {
        assertThat(service.fuzzySearch("   ")).isEmpty();
    }

    @Test
    void quickPicksReturnsOnlyTheCuratedNamesThatExist() {
        CommonDeliveryArea kilimani = area("Kilimani", "Nairobi", -1.2921, 36.7820);
        CommonDeliveryArea notCurated = area("Zimmerman", "Nairobi", -1.2100, 36.8900);
        when(repository.findAllByActiveTrue()).thenReturn(List.of(kilimani, notCurated));

        List<PlaceSuggestion> picks = service.quickPicks();

        assertThat(picks).extracting(PlaceSuggestion::description).contains("Kilimani, Nairobi");
        assertThat(picks).extracting(PlaceSuggestion::description).doesNotContain("Zimmerman, Nairobi");
    }

    @Test
    void everySuggestionCarriesTheSyntheticAreaIdAndResolvesBackToItsOwnCoordinates() {
        CommonDeliveryArea karen = area("Karen", "Nairobi", -1.3197, 36.7076);
        when(repository.findAllByActiveTrue()).thenReturn(List.of(karen));
        when(repository.findByIdAndActiveTrue(karen.getId())).thenReturn(java.util.Optional.of(karen));

        PlaceSuggestion suggestion = service.fuzzySearch("karen").get(0);
        assertThat(service.isAreaId(suggestion.placeId())).isTrue();

        PlaceDetails details = service.resolve(suggestion.placeId()).orElseThrow();
        assertThat(details.latitude()).isEqualByComparingTo(BigDecimal.valueOf(-1.3197));
        assertThat(details.longitude()).isEqualByComparingTo(BigDecimal.valueOf(36.7076));
        assertThat(details.address()).isEqualTo("Karen, Nairobi");
    }

    @Test
    void resolveIsEmptyForAnythingThatIsNotOneOfOurOwnIds() {
        assertThat(service.isAreaId("ChIJa-real-google-place-id")).isFalse();
        assertThat(service.resolve("ChIJa-real-google-place-id")).isEmpty();
        assertThat(service.resolve("area:not-a-uuid")).isEmpty();
    }

    @Test
    void nearestAreaLabelFindsTheClosestAreaWithinRangeAndKeepsTheOriginalCoordinates() {
        CommonDeliveryArea kilimani = area("Kilimani", "Nairobi", -1.2921, 36.7820);
        CommonDeliveryArea karen = area("Karen", "Nairobi", -1.3197, 36.7076);
        when(repository.findAllByActiveTrue()).thenReturn(List.of(kilimani, karen));

        // A point right next to Kilimani, not exactly on it.
        String label = service.nearestAreaLabel(new BigDecimal("-1.2925"), new BigDecimal("36.7825")).orElseThrow();
        assertThat(label).contains("Kilimani");
    }

    @Test
    void nearestAreaLabelIsEmptyWhenNothingSeededIsClose() {
        when(repository.findAllByActiveTrue()).thenReturn(List.of(area("Karen", "Nairobi", -1.3197, 36.7076)));

        // Mombasa — nowhere near any Nairobi-metro seeded area.
        assertThat(service.nearestAreaLabel(new BigDecimal("-4.0435"), new BigDecimal("39.6682"))).isEmpty();
    }
}
