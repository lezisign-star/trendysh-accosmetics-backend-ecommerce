package com.nbh.backend.imagecleanup;

import com.fasterxml.jackson.databind.JsonNode;
import com.nbh.backend.common.exception.ConfigurationException;
import lombok.extern.slf4j.Slf4j;
import com.nbh.backend.integrations.IntegrationKey;
import com.nbh.backend.integrations.IntegrationSettingsService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Server-side signed upload/delete to Cloudinary — the backend produces these images itself, so unlike staff
 * uploads (signed here, sent from the browser) it has to send them. Plain HTTP with the same SHA-1 signing the
 * rest of the app uses; no Cloudinary SDK.
 */
@Slf4j
@Service
public class CloudinaryUploader {

    public record Uploaded(String url, String publicId) {}

    private final ImageCleanupConfig config;
    private final IntegrationSettingsService settings;
    private final RestTemplate http = new RestTemplate();

    public CloudinaryUploader(ImageCleanupConfig config, IntegrationSettingsService settings) {
        this.config = config;
        this.settings = settings;
    }

    private String cloudName() {
        return settings.get(IntegrationKey.CLOUDINARY_CLOUD_NAME);
    }

    private String apiKey() {
        return settings.get(IntegrationKey.CLOUDINARY_API_KEY);
    }

    private String apiSecret() {
        return settings.get(IntegrationKey.CLOUDINARY_API_SECRET);
    }

    public boolean configured() {
        return settings.has(IntegrationKey.CLOUDINARY_CLOUD_NAME) && settings.has(IntegrationKey.CLOUDINARY_API_KEY)
                && settings.has(IntegrationKey.CLOUDINARY_API_SECRET);
    }

    public Uploaded upload(byte[] png, String folder, String publicId) {
        requireConfigured();
        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> signed = new TreeMap<>();
        signed.put("folder", folder);
        signed.put("public_id", publicId);
        signed.put("timestamp", String.valueOf(timestamp));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("file", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        form.add("api_key", apiKey());
        signed.forEach(form::add);
        form.add("signature", sign(signed));

        JsonNode result = post("/image/upload", form);
        String url = result.path("secure_url").asText(null);
        String id = result.path("public_id").asText(publicId);
        if (url == null) {
            throw new IllegalStateException("Cloudinary's reply had no secure_url: " + result);
        }
        return new Uploaded(url, id);
    }

    /** Best effort: a failure to delete is logged, never thrown — the database state is what matters. */
    public void delete(String publicId) {
        if (!configured() || publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            Map<String, String> signed = new TreeMap<>();
            signed.put("public_id", publicId);
            signed.put("timestamp", String.valueOf(timestamp));
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("api_key", apiKey());
            signed.forEach(form::add);
            form.add("signature", sign(signed));
            post("/image/destroy", form);
        } catch (RuntimeException e) {
            log.warn("Couldn't delete Cloudinary asset {}: {}", publicId, e.getMessage());
        }
    }

    private JsonNode post(String path, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String url = config.getCloudinaryUploadBaseUrl() + "/v1_1/" + cloudName() + path;
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class).getBody();
    }

    private String sign(Map<String, String> params) {
        String toSign = params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&")) + apiSecret();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ConfigurationException(
                    "Cloudinary is not configured — set CLOUDINARY_CLOUD_NAME/CLOUDINARY_API_KEY/CLOUDINARY_API_SECRET");
        }
    }
}
