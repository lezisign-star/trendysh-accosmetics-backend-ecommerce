package com.nbh.backend.variantjobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.nbh.backend.catalog.entity.ColorFamily;
import com.nbh.backend.catalog.service.ProductOptionsService;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiTextClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.variantgen.VariantCombos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the colours of a picture of variants placed together (owner, 2026-10-14: "a way to just take a picture of
 * product variants placed together, especially those of the same size but varying colours"). One AI call per picture,
 * with the photo itself; the answer is a short list for staff to look through and tick, never written anywhere here.
 *
 * <p>Each colour comes with a short shade name, its dot (the colour of the product itself, not the table or the hand
 * holding it), a code when one is printed on the item ("DH 03", "KE32"), and how many of it are in the picture. Any
 * size or volume printed on the packaging is listed once, separately.
 */
@Slf4j
@Service
public class PhotoColourReader {

    public record Found(String name, String hex, String code, int count, String colorFamily) {}

    public record Reading(List<Found> colours, List<String> sizes, String note) {}

    /** What is accepted: a phone photo is downscaled by the screen first, so this is generous. */
    static final int MAX_BYTES = 6 * 1024 * 1024;
    static final int MAX_COLOURS = 80;
    static final int MAX_SIZES = 8;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final GeminiTextClient gemini;
    private final ImageCleanupConfig config;

    public PhotoColourReader(GeminiTextClient gemini, ImageCleanupConfig config) {
        this.gemini = gemini;
        this.config = config;
    }

    /** {@code image} is the picture as base64 (a "data:" prefix is tolerated); {@code productName} only gives context. */
    public Reading read(String image, String mimeType, String productName) {
        String mime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(mime)) {
            throw new UnprocessableException("Use a JPEG, PNG or WebP picture.");
        }
        byte[] bytes = decode(image);
        if (bytes.length == 0) {
            throw new UnprocessableException("That picture is empty. Take or choose it again.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new UnprocessableException("That picture is too big (6 MB at most). Take it again a little further away or choose a smaller one.");
        }
        if (!config.geminiConfigured()) {
            throw new UnprocessableException("The AI isn't set up yet — add the GEMINI_API_KEY before reading pictures.");
        }
        JsonNode answer;
        try {
            answer = gemini.ask(new GeminiImageClient.Image(bytes, mime), prompt(productName), schema());
        } catch (GeminiImageClient.GeminiException e) {
            log.info("Reading a picture's colours failed: {}", e.getMessage());
            throw new UnprocessableException(e.isQuotaExceeded()
                    ? "The AI is busy at the moment (its limit for now is used up). Try again in a few minutes."
                    : "The AI couldn't read the picture: " + e.getMessage());
        }
        return tidy(answer);
    }

    // ---------------------------------------------------------------- the ask

    static String prompt(String productName) {
        String context = productName == null || productName.isBlank() ? ""
                : "The product is called \"" + productName.trim() + "\" (for context only: do not put the brand or "
                + "product name in the shade names).\n";
        return "This picture shows several variants of one kind of cosmetics product placed together, mostly the same "
                + "size and shape and differing in colour (for example nail polish bottles, lipsticks, lash glue or "
                + "eyeshadow pans).\n" + context
                + "List each DISTINCT colour variant you can see, once each.\n"
                + "For each one give: \"name\", a short plain shade name of one to three words like a shop would write "
                + "(\"Ruby Red\", \"Nude Beige\", \"Rose Gold\"); \"hex\", the \"#RRGGBB\" of the product's own colour "
                + "(the polish, the lipstick, the cap or the contents) and NOT of the background, table, hand, "
                + "reflections or label text; \"code\", the shade number or series printed on that item if there is one "
                + "(like \"DH 03\", \"KE32\", \"No. 12\"), else an empty string; and \"count\", how many items of that "
                + "colour are in the picture.\n"
                + "If a size or volume is printed on the packaging (like \"50ml\" or \"15g\"), list it once under "
                + "\"sizes\".\n"
                + "Do not invent items or codes you cannot see. If the picture shows no such products, return an empty "
                + "list. Answer with the JSON object only.";
    }

    static Map<String, Object> schema() {
        Map<String, Object> str = Map.of("type", "STRING");
        Map<String, Object> item = Map.of("type", "OBJECT",
                "properties", Map.of("name", str, "hex", str, "code", str, "count", Map.of("type", "INTEGER")),
                "required", List.of("name", "hex"));
        return Map.of("type", "OBJECT",
                "properties", Map.of(
                        "colours", Map.of("type", "ARRAY", "items", item),
                        "sizes", Map.of("type", "ARRAY", "items", str),
                        "note", str),
                "required", List.of("colours"));
    }

    // ---------------------------------------------------------------- the answer, made safe

    /** What the AI said, cleaned: valid dots only, short names, repeats merged, bounded. Never throws on a odd shape. */
    static Reading tidy(JsonNode answer) {
        Map<String, Found> byKey = new LinkedHashMap<>();
        int n = 0;
        for (JsonNode c : answer.path("colours")) {
            if (byKey.size() >= MAX_COLOURS) {
                break;
            }
            n++;
            String hex = VariantCombos.normaliseHex(c.path("hex").asText(null));
            String name = cut(ProductOptionsService.clean(c.path("name").asText(null)), 40);
            String code = cut(ProductOptionsService.clean(c.path("code").asText(null)), 24);
            if (name == null && hex == null && code == null) {
                continue;
            }
            if (name == null) {
                name = code != null ? "Colour " + code : "Colour " + n;
            }
            int count = Math.max(1, Math.min(999, c.path("count").asInt(1)));
            String key = name.toLowerCase(Locale.ROOT) + "|" + (code == null ? "" : code.toLowerCase(Locale.ROOT));
            Found seen = byKey.get(key);
            if (seen != null) {
                byKey.put(key, new Found(seen.name(), seen.hex() != null ? seen.hex() : hex, seen.code(),
                        Math.min(999, seen.count() + count), seen.colorFamily()));
                continue;
            }
            ColorFamily family = hex == null ? null : ColorFamily.fromHex(hex);
            byKey.put(key, new Found(name, hex, code, count, family == null ? null : family.name()));
        }
        Map<String, String> sizes = new LinkedHashMap<>();
        for (JsonNode s : answer.path("sizes")) {
            String size = cut(ProductOptionsService.clean(s.asText(null)), 20);
            if (size != null && sizes.size() < MAX_SIZES) {
                // "50ml", "50 ML" and "50 ml" are one size: the key ignores case and spaces, the first spelling is kept.
                sizes.putIfAbsent(size.toLowerCase(Locale.ROOT).replace(" ", ""), size);
            }
        }
        String note = cut(ProductOptionsService.clean(answer.path("note").asText(null)), 200);
        return new Reading(new ArrayList<>(byKey.values()), new ArrayList<>(sizes.values()), note);
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max).trim() : text;
    }

    private static byte[] decode(String image) {
        if (image == null || image.isBlank()) {
            throw new UnprocessableException("There is no picture to read.");
        }
        String data = image.trim();
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) {
            data = data.substring(comma + 1);
        }
        try {
            return Base64.getMimeDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("That isn't a picture the AI can read. Take or choose it again.");
        }
    }
}
