package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Gemini image-editing client (gemini-2.5-flash-image): sends a real photo plus an instruction and returns the
 * edited image. One retry after a short pause on a 429 or a transient 5xx/timeout; every other rejection (a
 * safety block, a malformed request) is reported verbatim with Gemini's own reason and never retried or
 * guessed at. A rate-limit that survives the retry, or any response naming RESOURCE_EXHAUSTED/quota, is flagged
 * {@code quotaExceeded} so the runner stops the whole batch instead of failing every remaining product the
 * same way.
 */
@Slf4j
@Service
public class GeminiImageClient {

    public static class GeminiException extends RuntimeException {
        private final boolean quotaExceeded;

        public GeminiException(String message, boolean quotaExceeded) {
            super(message);
            this.quotaExceeded = quotaExceeded;
        }

        public boolean isQuotaExceeded() {
            return quotaExceeded;
        }
    }

    public record Image(byte[] bytes, String mimeType) {}

    private final ImageCleanupConfig config;
    private final RestTemplate http;

    public GeminiImageClient(ImageCleanupConfig config) {
        this.config = config;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(90_000);
        this.http = new RestTemplate(factory);
    }

    /** Downloads the reference photo; the mime type comes from the response, not a guess. */
    public Image download(String url) {
        try {
            var response = http.exchange(url, HttpMethod.GET, null, byte[].class);
            byte[] bytes = response.getBody();
            if (bytes == null || bytes.length == 0) {
                throw new GeminiException("The reference photo was empty: " + url, false);
            }
            MediaType type = response.getHeaders().getContentType();
            return new Image(bytes, type != null ? type.toString() : "image/jpeg");
        } catch (HttpClientErrorException | HttpServerErrorException | ResourceAccessException e) {
            throw new GeminiException("Couldn't download the reference photo: " + e.getMessage(), false);
        }
    }

    public Image edit(Image reference, String prompt) {
        return edit(reference, java.util.List.of(), prompt);
    }

    /**
     * As {@link #edit(Image, String)}, with more photos after the one being edited (a real product to look like); the
     * prompt says what each is for.
     */
    public Image edit(Image reference, java.util.List<Image> extras, String prompt) {
        return edit(reference, extras, prompt, null);
    }

    /**
     * As {@link #edit(Image, List, String)}, asking for a picture of {@code aspectRatio} ("16:9", "1:1"…; null leaves
     * the shape to Gemini). A model that refuses the shape is asked again without it rather than failing.
     */
    public Image edit(Image reference, java.util.List<Image> extras, String prompt, String aspectRatio) {
        if (aspectRatio == null) return editShaped(reference, extras, prompt, null);
        try {
            return editShaped(reference, extras, prompt, aspectRatio);
        } catch (GeminiException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (!m.contains("http 400") || !(m.contains("aspect") || m.contains("imageconfig"))) throw e;
            log.warn("Gemini model {} refused aspect ratio {}; asking without it", config.getGeminiModel(), aspectRatio);
            return editShaped(reference, extras, prompt, null);
        }
    }

    private Image editShaped(Image reference, java.util.List<Image> extras, String prompt, String aspectRatio) {
        if (!config.geminiConfigured()) {
            throw new GeminiException("Gemini isn't configured — set GEMINI_API_KEY.", false);
        }
        java.util.List<Object> list = new java.util.ArrayList<>();
        list.add(Map.of("text", prompt));
        list.add(Map.of("inlineData", Map.of("mimeType", reference.mimeType(),
                "data", Base64.getEncoder().encodeToString(reference.bytes()))));
        for (Image extra : extras) {
            list.add(Map.of("inlineData", Map.of("mimeType", extra.mimeType(),
                    "data", Base64.getEncoder().encodeToString(extra.bytes()))));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("contents", List.of(Map.of("parts", list.toArray())));
        if (aspectRatio != null) body.put("generationConfig", Map.of("imageConfig", Map.of("aspectRatio", aspectRatio)));
        try {
            return attempt(body);
        } catch (HttpServerErrorException | ResourceAccessException first) {
            pause();
            try {
                return attempt(body);
            } catch (HttpServerErrorException e) {
                throw new GeminiException("Gemini failed (server error " + e.getStatusCode().value() + "): "
                        + e.getResponseBodyAsString(), false);
            } catch (ResourceAccessException e) {
                throw new GeminiException("Gemini timed out or was unreachable.", false);
            } catch (HttpClientErrorException e) {
                throw rejected(e);
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 429) {
                pause();
                try {
                    return attempt(body);
                } catch (HttpClientErrorException again) {
                    throw new GeminiException("Gemini is still rate-limiting after one retry (HTTP "
                            + again.getStatusCode().value() + "): " + again.getResponseBodyAsString(), true);
                } catch (HttpServerErrorException | ResourceAccessException other) {
                    throw new GeminiException("Gemini failed on the retry after a rate limit: " + other.getMessage(), false);
                }
            }
            throw rejected(e);
        }
    }

    private GeminiException rejected(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        boolean quota = e.getStatusCode().value() == 429 || (body != null
                && (body.contains("RESOURCE_EXHAUSTED") || body.toLowerCase(Locale.ROOT).contains("quota")));
        log.warn("Gemini rejected the request (HTTP {}): {}", e.getStatusCode().value(), body);
        return new GeminiException("Gemini rejected the request (HTTP " + e.getStatusCode().value() + "): " + body, quota);
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

    private Image attempt(Map<String, Object> body) {
        return limiter == null ? attemptNow(body) : limiter.call(() -> attemptNow(body));
    }

    private Image attemptNow(Map<String, Object> body) {
        String url = config.getGeminiBaseUrl() + "/v1beta/models/" + config.getGeminiModel() + ":generateContent";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", config.effectiveGeminiKey());
        JsonNode response = http.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class).getBody();
        if (response == null) {
            throw new GeminiException("Gemini returned an empty response.", false);
        }
        JsonNode candidate = response.path("candidates").path(0);
        JsonNode responseParts = candidate.path("content").path("parts");
        if (responseParts.isArray()) {
            for (JsonNode part : responseParts) {
                JsonNode inline = part.path("inlineData");
                String data = inline.path("data").asText(null);
                if (data != null && !data.isBlank()) {
                    return new Image(Base64.getDecoder().decode(data), inline.path("mimeType").asText("image/png"));
                }
            }
        }
        throw new GeminiException("Gemini returned no image — " + whyNoImage(response, candidate), false);
    }

    /** Only what Gemini itself says: a prompt-level block, then the candidate's finish reason, else the raw reply. */
    private String whyNoImage(JsonNode response, JsonNode candidate) {
        String block = response.path("promptFeedback").path("blockReason").asText(null);
        if (block != null && !block.isBlank()) {
            return "the prompt was blocked (blockReason=" + block + ").";
        }
        String finish = candidate.path("finishReason").asText(null);
        if (finish != null && !finish.isBlank() && !"STOP".equals(finish)) {
            return "it stopped without an image (finishReason=" + finish + ").";
        }
        return "no reason given; raw reply: " + response;
    }
}
