package com.nbh.backend.imagecleanup;

import com.nbh.backend.integrations.IntegrationKey;
import com.nbh.backend.integrations.IntegrationSettingsService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deleting a photo must really reach Cloudinary (owner, 2026-10-07): a signed {@code destroy} call that also clears the
 * CDN's cached copy, and that never throws into the staff member's request. Run against a local stand-in for Cloudinary.
 */
class CloudinaryUploaderTest {

    private static final String SECRET = "test-secret";

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void startStandIn() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            forms.add(parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStandIn() {
        server.stop(0);
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return fields;
    }

    private CloudinaryUploader uploader(boolean configured) {
        IntegrationSettingsService settings = mock(IntegrationSettingsService.class);
        when(settings.has(any())).thenReturn(configured);
        when(settings.get(IntegrationKey.CLOUDINARY_CLOUD_NAME)).thenReturn("demo");
        when(settings.get(IntegrationKey.CLOUDINARY_API_KEY)).thenReturn("key-123");
        when(settings.get(IntegrationKey.CLOUDINARY_API_SECRET)).thenReturn(SECRET);
        ImageCleanupConfig config = new ImageCleanupConfig();
        config.setCloudinaryUploadBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new CloudinaryUploader(config, settings);
    }

    private static String sha1(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void deletingSendsASignedDestroyThatAlsoClearsTheCdnCopy() throws Exception {
        uploader(true).delete("trendysh/products/abc");

        assertThat(paths).containsExactly("/v1_1/demo/image/destroy");
        Map<String, String> form = forms.get(0);
        assertThat(form).containsEntry("public_id", "trendysh/products/abc").containsEntry("invalidate", "true")
                .containsEntry("api_key", "key-123");
        // Cloudinary signs the parameters in alphabetical order with the secret on the end.
        String expected = sha1("invalidate=true&public_id=trendysh/products/abc&timestamp=" + form.get("timestamp") + SECRET);
        assertThat(form.get("signature")).isEqualTo(expected);
    }

    @Test
    void withoutCloudinaryCredentialsNothingIsSent() {
        uploader(false).delete("trendysh/products/abc");
        uploader(true).delete("  ");
        uploader(true).delete(null);

        assertThat(paths).isEmpty();
    }

    @Test
    void aFailureOnCloudinarysSideIsLoggedNeverThrown() {
        status = 500;

        uploader(true).delete("trendysh/products/abc"); // does not throw

        assertThat(paths).hasSize(1);
    }
}
