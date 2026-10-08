package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pieces of description drafting that need no Spring: the length limits, the prompt, and Gemini error handling. */
class DescriptionUnitTest {

    // ---- limits ----

    @Test
    void textIsCutAtTheLastWordBoundaryWithinTheLimitAndNeverMidWord() {
        String text = "alpha beta gamma delta epsilon";
        String cut = DescriptionCaps.fit(text, 18);
        assertThat(cut.length()).isLessThanOrEqualTo(18);
        assertThat(text).startsWith(cut);
        assertThat(cut).isEqualTo("alpha beta gamma");
        assertThat(DescriptionCaps.fit("short", 18)).isEqualTo("short");
        assertThat(DescriptionCaps.fit("   ", 18)).isNull();
        assertThat(DescriptionCaps.fit(null, 18)).isNull();
        // One huge word with no space still respects the limit.
        assertThat(DescriptionCaps.fit("x".repeat(50), 10)).hasSize(10);
    }

    @Test
    void highlightsAreCappedInNumberAndLengthAndBlankOnesDropped() {
        String stored = DescriptionCaps.fitHighlights(List.of("One", " ", "Two", "Three", "Four", "Five", "Six",
                "word ".repeat(40)));
        List<String> back = DescriptionCaps.splitHighlights(stored);
        assertThat(back).hasSize(DescriptionCaps.HIGHLIGHTS_COUNT).containsExactly("One", "Two", "Three", "Four", "Five");
        assertThat(DescriptionCaps.fitHighlights(List.of("   "))).isNull();
        assertThat(DescriptionCaps.splitHighlights(null)).isEmpty();
        String longOne = DescriptionCaps.fitHighlights(List.of("word ".repeat(40)));
        assertThat(longOne.length()).isLessThanOrEqualTo(DescriptionCaps.HIGHLIGHT);
    }

    @Test
    void textAPersonTypedIsCheckedAgainstEveryLimit() {
        assertThat(DescriptionCaps.firstTooLong("ok", "ok", List.of("a"), "ok", "ok", "ok", "ok")).isNull();
        assertThat(DescriptionCaps.firstTooLong("x".repeat(141), null, null, null, null, null, null)).contains("short description");
        assertThat(DescriptionCaps.firstTooLong(null, "x".repeat(701), null, null, null, null, null)).contains("description is over");
        assertThat(DescriptionCaps.firstTooLong(null, null, List.of("a", "b", "c", "d", "e", "f"), null, null, null, null)).contains("at most");
        assertThat(DescriptionCaps.firstTooLong(null, null, null, null, null, "x".repeat(61), null)).contains("search title");
        assertThat(DescriptionCaps.firstTooLong(null, null, null, null, null, null, "x".repeat(156))).contains("search description");
    }

    // ---- prompt ----

    @Test
    void thePromptStatesTheLimitsAndForbidsInventingFacts() {
        String p = new DescriptionPromptBuilder().build("Acrylic Powder 100g", List.of("Nails"), null);
        assertThat(p).contains("Acrylic Powder 100g").contains("Nails")
                .contains("WRITE ONLY WHAT IS SUPPORTED")
                .contains(String.valueOf(DescriptionCaps.SHORT_DESCRIPTION))
                .contains(String.valueOf(DescriptionCaps.DESCRIPTION))
                .contains("ONLY if the ingredients are readable")
                .doesNotContain("treat as true");
        assertThat(new DescriptionPromptBuilder().build("X", List.of(), "contains 30ml")).contains("contains 30ml").contains("treat as true");
    }

    // ---- Gemini text client against a local stand-in ----

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int firstStatus = 200;
    private volatile int laterStatus = 200;
    private volatile String body = "";
    private volatile String lastRequest = "";

    @BeforeEach
    void startStandIn() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = calls.incrementAndGet();
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(n == 1 ? firstStatus : laterStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStandIn() {
        server.stop(0);
    }

    private GeminiTextClient client() {
        ImageCleanupConfig config = new ImageCleanupConfig();
        config.setGeminiApiKey("test-key");
        config.setGeminiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new GeminiTextClient(config, new ObjectMapper());
    }

    private static final GeminiImageClient.Image PHOTO = new GeminiImageClient.Image(new byte[]{1}, "image/png");

    private static String answer(String json) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + json.replace("\"", "\\\"") + "\"}]}}]}";
    }

    @Test
    void withNoPhotoThePromptSaysSoAndNothingButTextIsSent() {
        String p = new DescriptionPromptBuilder().build("Gel Polish Pink", List.of("Nails"), "15ml bottle", false);
        assertThat(p).contains("NO photo").contains("15ml bottle").doesNotContain("attached image")
                .doesNotContain("readable on the label");
        body = answer("{\"shortDescription\":\"Hi\"}");
        assertThat(client().describe(null, "x").get("shortDescription").asText()).isEqualTo("Hi");
        assertThat(lastRequest).doesNotContain("inlineData");
        client().describe(PHOTO, "x");
        assertThat(lastRequest).contains("inlineData");
    }

    @Test
    void aGoodReplyIsParsedFromTheJsonTextInsideIt() {
        body = answer("{\"shortDescription\":\"Hi\"}");
        assertThat(client().describe(PHOTO, "x").get("shortDescription").asText()).isEqualTo("Hi");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aTransientServerErrorIsRetriedOnceAndThenSucceeds() {
        firstStatus = 503;
        body = answer("{\"shortDescription\":\"Hi\"}");
        assertThat(client().describe(PHOTO, "x").get("shortDescription").asText()).isEqualTo("Hi");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aRateLimitThatSurvivesTheRetryIsFlaggedAsQuota() {
        firstStatus = 429;
        laterStatus = 429;
        body = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}";
        assertThatThrownBy(() -> client().describe(PHOTO, "x"))
                .isInstanceOfSatisfying(GeminiImageClient.GeminiException.class,
                        e -> assertThat(e.isQuotaExceeded()).isTrue());
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aReplyThatIsNotTheExpectedJsonIsReportedNotGuessedAt() {
        body = answer("this is not json");
        assertThatThrownBy(() -> client().describe(PHOTO, "x"))
                .isInstanceOf(GeminiImageClient.GeminiException.class).hasMessageContaining("expected shape");
    }

    @Test
    void aBlockedRequestExplainsWhyUsingOnlyWhatGeminiSaid() {
        body = "{\"promptFeedback\":{\"blockReason\":\"OTHER\"},\"candidates\":[]}";
        assertThatThrownBy(() -> client().describe(PHOTO, "x"))
                .isInstanceOf(GeminiImageClient.GeminiException.class).hasMessageContaining("OTHER");
    }

    @Test
    void withoutAnApiKeyNothingIsSent() {
        ImageCleanupConfig config = new ImageCleanupConfig();
        config.setGeminiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        assertThatThrownBy(() -> new GeminiTextClient(config, new ObjectMapper()).describe(PHOTO, "x"))
                .isInstanceOf(GeminiImageClient.GeminiException.class).hasMessageContaining("GEMINI_API_KEY");
        assertThat(calls.get()).isZero();
    }
}
