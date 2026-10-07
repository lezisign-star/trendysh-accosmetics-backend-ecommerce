package com.nbh.backend.imagecleanup;

import com.nbh.backend.integrations.SecretCipher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pieces of photo cleanup that don't need Spring: logo stamping, prompts, credential encryption, Gemini error handling. */
class ImageCleanupUnitTest {

    // ---- watermark ----

    private static byte[] solidPng(int size, Color color) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, size, size);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void theLogoLandsInTheCentreFaintlyAndLeavesTheRestOfThePictureAlone() throws Exception {
        Color cream = new Color(0xF3, 0xEB, 0xDD);
        byte[] stamped = new WatermarkService().stamp(solidPng(512, cream));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(stamped));

        assertThat(out.getWidth()).isEqualTo(512);
        assertThat(out.getHeight()).isEqualTo(512);
        // Corners and edges are untouched.
        for (int[] p : new int[][]{{0, 0}, {511, 0}, {0, 511}, {511, 511}, {256, 10}, {10, 256}}) {
            assertThat(new Color(out.getRGB(p[0], p[1]), true)).as("pixel " + p[0] + "," + p[1]).isEqualTo(cream);
        }
        // Something was drawn in the central ~20% square.
        int changed = 0;
        int max = 0;
        for (int x = 205; x < 307; x++) {
            for (int y = 205; y < 307; y++) {
                Color c = new Color(out.getRGB(x, y), true);
                int d = Math.abs(c.getRed() - cream.getRed()) + Math.abs(c.getGreen() - cream.getGreen())
                        + Math.abs(c.getBlue() - cream.getBlue());
                if (d > 4) changed++;
                max = Math.max(max, d);
            }
        }
        assertThat(changed).as("pixels changed by the logo").isGreaterThan(300);
        // 30% opacity: even the darkest logo pixel stays far from a full-strength overlay.
        assertThat(max).as("strongest change").isLessThan(3 * 255 * 7 / 10);
    }

    @Test
    void anUndecodablePictureIsReturnedUntouchedRatherThanFailing() {
        byte[] junk = "not an image".getBytes(StandardCharsets.UTF_8);
        assertThat(new WatermarkService().stamp(junk)).isEqualTo(junk);
    }

    // ---- prompts ----

    @Test
    void everyViewIsDifferentAndEveryPromptProtectsTheProduct() {
        ImageCleanupPromptBuilder builder = new ImageCleanupPromptBuilder();
        assertThat(PhotoView.values()).as("the views staff can tick").hasSize(8);
        Set<String> distinct = new HashSet<>();
        for (PhotoView view : PhotoView.values()) {
            String p = builder.build("Acrylic Powder 100g", view, null, "#F3EBDD");
            distinct.add(p);
            assertThat(p).contains("Acrylic Powder 100g").contains("#F3EBDD").contains("creamy studio backdrop")
                    .contains("Do not add any watermark").contains("Do not add a lid").contains(view.instruction());
        }
        assertThat(distinct).hasSize(8);
    }

    @Test
    void theFirstSixViewsAreTheOriginalShotsInTheirOldOrderAndTheDefaultsAreTheFirstThree() {
        assertThat(PhotoView.firstOf(6)).containsExactly(PhotoView.CLEAN, PhotoView.DETAIL, PhotoView.WIDE,
                PhotoView.FRONT, PhotoView.ELEVATED, PhotoView.SIDE);
        assertThat(PhotoView.firstOf(3)).containsExactly(PhotoView.CLEAN, PhotoView.DETAIL, PhotoView.WIDE);
        assertThat(PhotoView.firstOf(0)).as("never fewer than one").hasSize(1);
        assertThat(PhotoView.firstOf(99)).as("never more than exist").hasSize(8);
    }

    @Test
    void viewKeysAreReadBackWhateverTheirCase() {
        assertThat(PhotoView.parse("top_down")).contains(PhotoView.TOP_DOWN);
        assertThat(PhotoView.parse(" Back ")).contains(PhotoView.BACK);
        assertThat(PhotoView.parse("sideways")).isEmpty();
        assertThat(PhotoView.parse(null)).isEmpty();
    }

    @Test
    void aStaffNoteIsPassedOnButTheRulesThatProtectTheProductStayAndWinOverIt() {
        ImageCleanupPromptBuilder builder = new ImageCleanupPromptBuilder();
        String p = builder.build("Nail Lamp", PhotoView.FRONT, "  on a pink marble surface,\n lid OFF  ", "#F3EBDD");

        assertThat(p).contains("on a pink marble surface, lid OFF").contains("The shop's own notes")
                .contains("NEVER change the product itself").contains("STRICT RULES above always win");
        assertThat(p.indexOf("STRICT RULES:")).as("the rules come before the note").isLessThan(p.indexOf("The shop's own notes"));
    }

    @Test
    void noNoteMeansNoNotesBlockAndALongOneIsCutToTheLimit() {
        ImageCleanupPromptBuilder builder = new ImageCleanupPromptBuilder();
        assertThat(builder.build("Lamp", PhotoView.CLEAN, null, "#F3EBDD")).doesNotContain("The shop's own notes");
        assertThat(builder.build("Lamp", PhotoView.CLEAN, "   \n ", "#F3EBDD")).doesNotContain("The shop's own notes");
        assertThat(ImageCleanupPromptBuilder.cleanNote("x".repeat(900))).hasSize(ImageCleanupPromptBuilder.MAX_NOTE_LENGTH);
        assertThat(ImageCleanupPromptBuilder.cleanNote(null)).isNull();
    }

    // ---- credentials at rest ----

    @Test
    void encryptedCredentialsRoundTripAreSaltedAndRejectTampering() {
        SecretCipher cipher = new SecretCipher("test-key", "jwt-fallback");
        String a = cipher.encrypt("super-secret");
        String b = cipher.encrypt("super-secret");
        assertThat(a).isNotEqualTo(b).doesNotContain("super-secret");
        assertThat(cipher.decrypt(a)).isEqualTo("super-secret");

        byte[] raw = Base64.getDecoder().decode(a);
        raw[raw.length - 1] ^= 1;
        assertThat(cipher.decrypt(Base64.getEncoder().encodeToString(raw))).isNull();
        assertThat(new SecretCipher("another-key", "x").decrypt(a)).isNull();
        assertThat(cipher.decrypt("%%%not-base64%%%")).isNull();
    }

    @Test
    void theJwtSecretIsTheKeyWhenNoDedicatedOneIsConfigured() {
        SecretCipher withFallback = new SecretCipher("", "jwt-secret");
        assertThat(new SecretCipher("jwt-secret", "ignored").decrypt(withFallback.encrypt("v"))).isEqualTo("v");
    }

    // ---- Gemini client against a local stand-in ----

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int firstStatus = 200;
    private volatile int laterStatus = 200;
    private volatile String body = "";

    @BeforeEach
    void startStandIn() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
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

    private GeminiImageClient client() {
        ImageCleanupConfig config = new ImageCleanupConfig();
        config.setGeminiApiKey("test-key");
        config.setGeminiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new GeminiImageClient(config);
    }

    private static final GeminiImageClient.Image REF = new GeminiImageClient.Image(new byte[]{1, 2, 3}, "image/png");

    @Test
    void aSuccessfulReplyReturnsTheImageBytes() throws Exception {
        body = "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                + Base64.getEncoder().encodeToString(solidPng(8, Color.RED)) + "\"}}]}}]}";
        GeminiImageClient.Image out = client().edit(REF, "clean it");
        assertThat(out.mimeType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new ByteArrayInputStream(out.bytes())).getWidth()).isEqualTo(8);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aSafetyBlockIsReportedVerbatimNeverRetriedAndNotTreatedAsQuota() {
        firstStatus = 400;
        laterStatus = 400;
        body = "{\"error\":{\"message\":\"prompt blocked for SAFETY\"}}";
        assertThatThrownBy(() -> client().edit(REF, "x"))
                .isInstanceOfSatisfying(GeminiImageClient.GeminiException.class, e -> {
                    assertThat(e.getMessage()).contains("HTTP 400").contains("SAFETY");
                    assertThat(e.isQuotaExceeded()).isFalse();
                });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aRateLimitThatSurvivesTheOneRetryStopsTheBatchAsAQuotaProblem() {
        firstStatus = 429;
        laterStatus = 429;
        body = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}";
        assertThatThrownBy(() -> client().edit(REF, "x"))
                .isInstanceOfSatisfying(GeminiImageClient.GeminiException.class,
                        e -> assertThat(e.isQuotaExceeded()).isTrue());
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aTransientServerErrorIsRetriedOnceAndThenSucceeds() throws Exception {
        firstStatus = 503;
        laterStatus = 200;
        body = "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                + Base64.getEncoder().encodeToString(solidPng(4, Color.BLUE)) + "\"}}]}}]}";
        // the 503 reply body is the same JSON; only the status differs
        assertThat(client().edit(REF, "x").bytes()).isNotEmpty();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void aReplyWithNoImageExplainsWhyUsingOnlyWhatGeminiSaid() {
        body = "{\"promptFeedback\":{\"blockReason\":\"OTHER\"},\"candidates\":[]}";
        assertThatThrownBy(() -> client().edit(REF, "x"))
                .isInstanceOfSatisfying(GeminiImageClient.GeminiException.class, e -> {
                    assertThat(e.getMessage()).contains("blockReason=OTHER");
                    assertThat(e.isQuotaExceeded()).isFalse();
                });
    }

    @Test
    void withoutAnApiKeyNothingIsSentToGemini() {
        ImageCleanupConfig config = new ImageCleanupConfig();
        config.setGeminiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        assertThatThrownBy(() -> new GeminiImageClient(config).edit(REF, "x"))
                .isInstanceOf(GeminiImageClient.GeminiException.class).hasMessageContaining("GEMINI_API_KEY");
        assertThat(calls.get()).isZero();
    }
}
