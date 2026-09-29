package com.nbh.backend.tumaboda;

import com.nbh.backend.cart.service.CartService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * With no TumaBoda API key configured (the current production state — no partner account yet), every
 * maps endpoint must answer WITHOUT calling out to TumaBoda at all: an empty apiKey means {@code
 * TumaBodaConfig.enabled()} is false, and none of these methods should even construct a request. Direct
 * construction, no Spring context — the gate being tested is a plain `if` at the top of each method.
 */
class TumaBodaPublicControllerOffTest {

    private final TumaBodaConfig offConfig =
            new TumaBodaConfig("", "http://localhost/api", "", new BigDecimal("5000"), new BigDecimal("20000"));
    private final TumaBodaClient client = mock(TumaBodaClient.class);
    private final TumaBodaQuoteService quotes = mock(TumaBodaQuoteService.class);
    private final TumaBodaThrottle throttle = mock(TumaBodaThrottle.class);
    private final CartService cartService = mock(CartService.class);
    private final CommonDeliveryAreaService commonAreas = mock(CommonDeliveryAreaService.class);
    private final TumaBodaPublicController controller =
            new TumaBodaPublicController(offConfig, client, quotes, throttle, cartService, commonAreas);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void statusReportsDisabled() {
        assertThat(controller.status()).isEqualTo(Map.of("enabled", false));
    }

    @Test
    void autocompleteIsEmptyAndNeverCallsTumaBodaOrTheThrottle() {
        assertThat(controller.autocomplete("kilimani", request)).isEmpty();
        verifyNoInteractions(client, throttle, commonAreas);
    }

    @Test
    void quickPickAreasIsEmptyAndNeverTouchesTheSeededTableOrTheThrottle() {
        assertThat(controller.quickPickAreas(request)).isEmpty();
        verifyNoInteractions(throttle, commonAreas);
    }

    @Test
    void placeDetailsIs404RegardlessOfWhatThePlaceIdLooksLike() {
        assertThatThrownBy(() -> controller.placeDetails("ChIJ-some-real-place", request))
                .isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(client, throttle, commonAreas);
    }

    @Test
    void reverseGeocodeFallsBackToTheSeededTableWithoutCallingTumaBodaOrTheThrottle() {
        BigDecimal lat = new BigDecimal("-1.2921");
        BigDecimal lng = new BigDecimal("36.7820");

        controller.reverseGeocode(lat, lng, request);

        verify(commonAreas).nearestAreaLabel(lat, lng);
        verifyNoInteractions(client);
        verify(throttle, never()).lookup(org.mockito.ArgumentMatchers.any());
    }
}
