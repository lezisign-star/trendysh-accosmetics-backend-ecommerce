package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gemini text client for product descriptions: sends the product's real photo plus instructions to a text model on the
 * same API key as the photo editing, and asks for the answer as JSON in a fixed shape. Error handling matches
 * {@link GeminiImageClient}: one retry after a pause on a 429 or a transient failure, anything else reported verbatim
 * (never guessed at), and a rate limit that survives the retry flagged as quota so the runner can stop.
 */
@Slf4j
@Service
public class GeminiTextClient {

    private final ImageCleanupConfig config;
    private final ObjectMapper json;
    private final RestTemplate http;

    public GeminiTextClient(ImageCleanupConfig config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(60_000);
        this.http = new RestTemplate(factory);
    }

    /** The fixed shape of the answer: every field a string except the highlights, which is a list of strings. */
    static Map<String, Object> schema() {
        Map<String, Object> str = Map.of("type", "STRING");
        return Map.of("type", "OBJECT",
                "properties", Map.of(
                        "shortDescription", str,
                        "description", str,
                        "highlights", Map.of("type", "ARRAY", "items", str),
                        "ingredients", str,
                        "howToUse", str,
                        "seoTitle", str,
                        "seoDescription", str),
                "required", List.of("shortDescription", "description", "highlights", "ingredients", "howToUse",
                        "seoTitle", "seoDescription"));
    }

    /**
     * Asks for the description JSON about {@code photo} (null: no photo, the prompt alone); the parsed answer, or a
     * {@link GeminiImageClient.GeminiException}.
     */
    public JsonNode describe(GeminiImageClient.Image photo, String prompt) {
        return ask(photo, prompt, schema());
    }

    /** As {@link #describe}, for any answer shape: {@code schema} is a Gemini response schema. */
    public JsonNode ask(GeminiImageClient.Image photo, String prompt, Map<String, Object> schema) {
        if (!config.geminiConfigured()) {
            throw new GeminiImageClient.GeminiException("Gemini isn't configured — set GEMINI_API_KEY.", false);
        }
        List<Object> parts = new java.util.ArrayList<>();
        parts.add(Map.of("text", prompt));
        if (photo != null) {
            parts.add(Map.of("inlineData", Map.of("mimeType", photo.mimeType(),
                    "data", Base64.getEncoder().encodeToString(photo.bytes()))));
        }
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema,
                        "temperature", 0.4));
        try {
            return attempt(body);
        } catch (HttpServerErrorException | ResourceAccessException first) {
            pause();
            try {
                return attempt(body);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                throw new GeminiImageClient.GeminiException("Gemini couldn't be reached for the description: "
                        + e.getMessage(), false);
            } catch (HttpClientErrorException e) {
                throw rejected(e);
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                pause();
                try {
                    return attempt(body);
                } catch (HttpClientErrorException again) {
                    throw new GeminiImageClient.GeminiException("Gemini is still rate-limiting after one retry (HTTP "
                            + again.getStatusCode().value() + ").", true);
                } catch (HttpServerErrorException | ResourceAccessException other) {
                    throw new GeminiImageClient.GeminiException("Gemini failed on the retry after a rate limit: "
                            + other.getMessage(), false);
                }
            }
            throw rejected(e);
        }
    }

    private GeminiImageClient.GeminiException rejected(HttpClientErrorException e) {
        String text = e.getResponseBodyAsString();
        boolean quota = e.getStatusCode().value() == 429 || (text != null
                && (text.contains("RESOURCE_EXHAUSTED") || text.toLowerCase(Locale.ROOT).contains("quota")));
        log.warn("Gemini rejected the description request (HTTP {}): {}", e.getStatusCode().value(), text);
        return new GeminiImageClient.GeminiException(
                "Gemini rejected the description request (HTTP " + e.getStatusCode().value() + "): " + text, quota);
    }

    private void pause() {
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shared with every other Gemini caller (see {@link GeminiLimiter}); optional so hand-built instances still work. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GeminiLimiter limiter;

    private JsonNode attempt(Map<String, Object> body) {
        return limiter == null ? attemptNow(body) : limiter.call(() -> attemptNow(body));
    }

    private JsonNode attemptNow(Map<String, Object> body) {
        String url = config.getGeminiBaseUrl() + "/v1beta/models/" + config.getDescriptionModel() + ":generateContent";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", config.effectiveGeminiKey());
        JsonNode response = http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class).getBody();
        if (response == null) {
            throw new GeminiImageClient.GeminiException("Gemini returned an empty response.", false);
        }
        JsonNode candidate = response.path("candidates").path(0);
        String text = candidate.path("content").path("parts").path(0).path("text").asText(null);
        if (text == null || text.isBlank()) {
            String block = response.path("promptFeedback").path("blockReason").asText(null);
            String finish = candidate.path("finishReason").asText(null);
            throw new GeminiImageClient.GeminiException("Gemini wrote no description"
                    + (block != null ? " (the request was blocked: " + block + ")"
                    : finish != null && !"STOP".equals(finish) ? " (it stopped: " + finish + ")" : "") + ".", false);
        }
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new GeminiImageClient.GeminiException("Gemini's description wasn't in the expected shape.", false);
        }
    }
}
