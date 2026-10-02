package com.nbh.backend.integrations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for credentials stored in the database. The key comes from {@code app.secrets.key} (set
 * {@code APP_SECRETS_KEY} in production) and falls back to the JWT secret so a deployment that never set it still
 * has encryption at rest; rotating whichever it uses means re-entering the credentials.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.secrets.key:}") String configured,
                        @Value("${app.jwt.secret}") String jwtSecret) {
        String material = configured == null || configured.isBlank() ? jwtSecret : configured;
        try {
            this.key = new SecretKeySpec(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Couldn't encrypt the value", e);
        }
    }

    /** @return the plain text, or null if it can't be decrypted (e.g. the key was rotated). */
    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }
}
