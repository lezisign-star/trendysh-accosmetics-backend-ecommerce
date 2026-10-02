package com.nbh.backend.integrations;

import com.nbh.backend.common.exception.UnprocessableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the app reads third-party credentials from: a value saved in the admin dashboard wins, otherwise the
 * server environment variable it used before. Values are cached in memory and the cache is dropped on every
 * change, so saving a key takes effect immediately without a restart.
 */
@Service
public class IntegrationSettingsService {

    public record Status(String key, String label, boolean secret, boolean configured, String source, String hint) {}

    private final IntegrationSettingRepository repository;
    private final SecretCipher cipher;
    private final Map<IntegrationKey, String> env;
    private final Map<IntegrationKey, Optional<String>> cache = new ConcurrentHashMap<>();

    public IntegrationSettingsService(IntegrationSettingRepository repository, SecretCipher cipher,
                                      @Value("${app.image-cleanup.gemini-api-key:}") String geminiKey,
                                      @Value("${app.cloudinary.cloud-name:}") String cloudName,
                                      @Value("${app.cloudinary.api-key:}") String cloudKey,
                                      @Value("${app.cloudinary.api-secret:}") String cloudSecret) {
        this.repository = repository;
        this.cipher = cipher;
        this.env = Map.of(
                IntegrationKey.GEMINI_API_KEY, geminiKey,
                IntegrationKey.CLOUDINARY_CLOUD_NAME, cloudName,
                IntegrationKey.CLOUDINARY_API_KEY, cloudKey,
                IntegrationKey.CLOUDINARY_API_SECRET, cloudSecret);
    }

    /** The effective value: dashboard first, environment second; empty when neither is set. */
    public String get(IntegrationKey key) {
        return cache.computeIfAbsent(key, k -> Optional.ofNullable(stored(k)).or(() -> envValue(k))).orElse("");
    }

    public boolean has(IntegrationKey key) {
        return !get(key).isBlank();
    }

    @Transactional(readOnly = true)
    public List<Status> statuses() {
        return Arrays.stream(IntegrationKey.values()).map(k -> {
            String saved = stored(k);
            String value = saved != null ? saved : envValue(k).orElse("");
            String source = saved != null ? "DASHBOARD" : (value.isBlank() ? "NONE" : "ENVIRONMENT");
            return new Status(k.name(), k.label(), k.secret(), !value.isBlank(), source, hint(k, value));
        }).toList();
    }

    @Transactional
    public void set(IntegrationKey key, String value, UUID staffId) {
        if (value == null || value.isBlank()) {
            throw new UnprocessableException("Enter a value, or clear the setting instead.");
        }
        IntegrationSetting row = repository.findById(key.name()).orElseGet(IntegrationSetting::new);
        row.setKey(key.name());
        row.setValueEncrypted(cipher.encrypt(value.trim()));
        row.setUpdatedByUserId(staffId);
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        cache.remove(key);
    }

    /** Removes the dashboard value; the environment variable (if any) applies again. */
    @Transactional
    public void clear(IntegrationKey key) {
        repository.deleteById(key.name());
        cache.remove(key);
    }

    private String stored(IntegrationKey key) {
        return repository.findById(key.name()).map(r -> cipher.decrypt(r.getValueEncrypted())).orElse(null);
    }

    private Optional<String> envValue(IntegrationKey key) {
        String v = env.get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    /** Non-secret values are shown in full; secrets only as their last four characters. */
    private static String hint(IntegrationKey key, String value) {
        if (value.isBlank()) {
            return null;
        }
        if (!key.secret()) {
            return value;
        }
        return "••••" + value.substring(Math.max(0, value.length() - 4));
    }
}
