package com.nbh.backend.tumaboda;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The webhook endpoint is public, so what it will and will not read from a stranger matters as much as
 * what it does with a real TumaBoda message: with the integration off (no secret) or no signature
 * nothing is read at all, and a body past the size cap is refused without being buffered.
 */
class TumaBodaWebhookControllerTest {

    private final TumaBodaWebhookVerifier verifier = mock(TumaBodaWebhookVerifier.class);
    private final TumaBodaStatusService statuses = mock(TumaBodaStatusService.class);
    private final TumaBodaWebhookController controller =
            new TumaBodaWebhookController(verifier, statuses, new ObjectMapper());

    /** A request whose body must never be opened: reading it fails the test. */
    private static MockHttpServletRequest requestThatMustNotBeRead() {
        return new MockHttpServletRequest() {
            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("the body was read for a request that should have been refused first");
            }
        };
    }

    /** A request with no declared length (like chunked transfer) whose body never ends. */
    private static MockHttpServletRequest endlessRequest() {
        return new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return 'a';
                    }

                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener readListener) {
                        // blocking reads only
                    }
                };
            }
        };
    }

    private static MockHttpServletRequest requestWithBody(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        return request;
    }

    private ResponseEntity<Map<String, String>> receive(String signature, MockHttpServletRequest request)
            throws IOException {
        return controller.receive(signature, request);
    }

    @Test
    void withNoSecretConfiguredTheBodyIsNeverRead() throws Exception {
        when(verifier.configured()).thenReturn(false);

        assertThat(receive("t=1,v1=00", requestThatMustNotBeRead()).getStatusCode().value()).isEqualTo(401);

        verify(verifier, never()).verify(any(), any());
        verify(statuses, never()).apply(any(), any(), any());
    }

    @Test
    void withNoSignatureTheBodyIsNeverRead() throws Exception {
        when(verifier.configured()).thenReturn(true);

        assertThat(receive(null, requestThatMustNotBeRead()).getStatusCode().value()).isEqualTo(401);
        assertThat(receive("  ", requestThatMustNotBeRead()).getStatusCode().value()).isEqualTo(401);

        verify(verifier, never()).verify(any(), any());
        verify(statuses, never()).apply(any(), any(), any());
    }

    @Test
    void aBodyThatDeclaresItselfTooLargeIsRefusedWithoutBeingRead() throws Exception {
        when(verifier.configured()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return TumaBodaWebhookController.MAX_BODY_BYTES + 1L;
            }

            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("a body that declares itself too large must not be opened");
            }
        };

        assertThat(receive("t=1,v1=00", request).getStatusCode().value()).isEqualTo(413);

        verify(verifier, never()).verify(any(), any());
    }

    @Test
    void aBodyThatNeverEndsIsCutOffInsteadOfBuffered() throws Exception {
        when(verifier.configured()).thenReturn(true);

        // If the read were unbounded this call would never return.
        assertThat(receive("t=1,v1=00", endlessRequest()).getStatusCode().value()).isEqualTo(413);

        verify(verifier, never()).verify(any(), any());
        verify(statuses, never()).apply(any(), any(), any());
    }

    @Test
    void aBodyExactlyAtTheCapIsStillAccepted() throws Exception {
        when(verifier.configured()).thenReturn(true);
        when(verifier.verify(any(), any())).thenReturn(true);
        byte[] body = new byte[TumaBodaWebhookController.MAX_BODY_BYTES];

        // Not parseable as a webhook, but it was let in and answered 200 (TumaBoda must not retry-storm us).
        assertThat(receive("t=1,v1=00", requestWithBody(body)).getStatusCode().value()).isEqualTo(200);
        verify(verifier).verify(any(), any());
    }

    @Test
    void aBadSignatureIsRefusedAndNothingIsApplied() throws Exception {
        when(verifier.configured()).thenReturn(true);
        when(verifier.verify(any(), any())).thenReturn(false);
        byte[] body = "{\"event\":\"delivery.delivered\",\"data\":{\"id\":\"d-1\"}}".getBytes(StandardCharsets.UTF_8);

        assertThat(receive("t=1,v1=00", requestWithBody(body)).getStatusCode().value()).isEqualTo(401);

        verify(statuses, never()).apply(any(), any(), any());
    }

    @Test
    void aSignedStatusUpdateReachesTheStatusService() throws Exception {
        when(verifier.configured()).thenReturn(true);
        when(verifier.verify(any(), any())).thenReturn(true);
        byte[] body = ("{\"id\":\"evt_1\",\"event\":\"delivery.status_updated\","
                + "\"data\":{\"id\":\"d-1\",\"status\":\"in_transit\",\"riderId\":\"r-9\"}}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(receive("t=1,v1=00", requestWithBody(body)).getStatusCode().value()).isEqualTo(200);

        verify(statuses).apply("d-1", "in_transit", "r-9");
    }

    @Test
    void aDeliveredEventWithNoStatusIsReadAsDelivered() throws Exception {
        when(verifier.configured()).thenReturn(true);
        when(verifier.verify(any(), any())).thenReturn(true);
        byte[] body = "{\"event\":\"delivery.delivered\",\"data\":{\"id\":\"d-2\"}}".getBytes(StandardCharsets.UTF_8);

        assertThat(receive("t=1,v1=00", requestWithBody(body)).getStatusCode().value()).isEqualTo(200);

        verify(statuses).apply("d-2", "delivered", null);
    }
}
