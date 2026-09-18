package com.nbh.backend.catalog.service;

import com.nbh.backend.common.exception.ConfigurationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Backend-signed direct upload: staff request a short-lived signature here,
 * upload the file straight to Cloudinary from the browser using it, then POST
 * the resulting secure_url to a product's images endpoint. The Cloudinary API
 * secret never reaches the frontend — only the signature does, and it's only
 * valid for the exact params it was computed over (Cloudinary re-derives and
 * compares it server-side on upload).
 *
 * Deliberately no Cloudinary SDK dependency — the signing algorithm is public
 * and small (sort every param except file/api_key/signature/resource_type
 * alphabetically, join as {@code key=value&key=value}, append the api secret,
 * SHA-1, hex-encode; see Cloudinary's own "Generating authentication
 * signatures" docs), so a plain {@link MessageDigest} does the whole job
 * without pulling in a client library this backend otherwise has no use for.
 */
@Service
public class CloudinarySignatureService {

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;

    public CloudinarySignatureService(
            @Value("${app.cloudinary.cloud-name:}") String cloudName,
            @Value("${app.cloudinary.api-key:}") String apiKey,
            @Value("${app.cloudinary.api-secret:}") String apiSecret) {
        this.cloudName = cloudName;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    public UploadSignature sign(String folder) {
        if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw new ConfigurationException(
                    "Cloudinary is not configured — set CLOUDINARY_CLOUD_NAME/CLOUDINARY_API_KEY/CLOUDINARY_API_SECRET");
        }

        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> paramsToSign = new TreeMap<>();
        paramsToSign.put("timestamp", String.valueOf(timestamp));
        if (folder != null && !folder.isBlank()) {
            paramsToSign.put("folder", folder);
        }

        String toSign = paramsToSign.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        String signature = sha1Hex(toSign + apiSecret);

        return new UploadSignature(cloudName, apiKey, timestamp, signature, folder);
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available on this JVM", e);
        }
    }

    public record UploadSignature(String cloudName, String apiKey, long timestamp, String signature, String folder) {}
}
