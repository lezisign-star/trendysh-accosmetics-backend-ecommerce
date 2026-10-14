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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Reads the colours of a picture of variants placed together (owner, 2026-10-14: "a way to just take a picture of
 * product variants placed together, especially those of the same size but varying colours"). The answer is a list for
 * staff to look through and tick, never written anywhere here.
 *
 * <p>Every shade is listed separately (owner, same day, after one look at a counter of about 200 gel polish bottles
 * gave 19 lumped colours): a big picture is read in sections ({@link PictureSections}), the AI lists every item in a
 * section with where its centre is, each item is kept by the one section that owns its centre, and items the AI named
 * the same and that look the same are one shade with a count. Each shade has a short name, its dot (the product's own
 * colour, not the table or the hand holding it), a code when one is printed on the item ("DH 03", "KE32"), and how
 * many of it were seen. Any size printed on the packaging is listed once, separately.
 */
@Slf4j
@Service
public class PhotoColourReader {

    public record Found(String name, String hex, String code, int count, String colorFamily) {}

    public record Reading(List<Found> colours, List<String> sizes, String note) {}

    /** What is accepted: a phone photo is downscaled by the screen first, so this is generous. */
    static final int MAX_BYTES = 6 * 1024 * 1024;
    static final int MAX_COLOURS = 300;
    static final int MAX_SIZES = 8;
    /** Items given the same name in two sections are one shade only when their dots are this close (CIE76). */
    static final double SAME_SHADE = 15;
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
        List<PictureSections.Section> sections = PictureSections.cut(new GeminiImageClient.Image(bytes, mime));
        String prompt = prompt(productName, sections.size() > 1);

        // The sections are read side by side; the shared Gemini limiter keeps the pace within the quota.
        List<JsonNode> answers = new ArrayList<>();
        List<GeminiImageClient.GeminiException> failures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonNode>> calls = new ArrayList<>();
            for (PictureSections.Section s : sections) {
                calls.add(pool.submit(() -> gemini.ask(s.image(), prompt, schema())));
            }
            for (Future<JsonNode> call : calls) {
                try {
                    answers.add(call.get());
                } catch (ExecutionException e) {
                    answers.add(null);
                    if (e.getCause() instanceof GeminiImageClient.GeminiException g) {
                        failures.add(g);
                    } else {
                        log.warn("Reading a part of a picture failed", e.getCause());
                        failures.add(new GeminiImageClient.GeminiException(String.valueOf(e.getCause().getMessage()), false));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UnprocessableException("That didn't finish. Try again.");
                }
            }
        }
        if (failures.size() == sections.size()) {
            GeminiImageClient.GeminiException e = failures.get(0);
            log.info("Reading a picture's colours failed: {}", e.getMessage());
            boolean quota = failures.stream().anyMatch(GeminiImageClient.GeminiException::isQuotaExceeded);
            throw new UnprocessableException(quota
                    ? "The AI is busy at the moment (its limit for now is used up). Try again in a few minutes."
                    : "The AI couldn't read the picture: " + e.getMessage());
        }
        Reading reading = combine(sections, answers);
        if (!failures.isEmpty()) {
            boolean quota = failures.stream().anyMatch(GeminiImageClient.GeminiException::isQuotaExceeded);
            String missed = failures.size() + " of the " + sections.size() + " parts of the picture couldn't be read"
                    + (quota ? " (the AI is busy)" : "") + ", so some colours may be missing. Try the picture again for the rest.";
            reading = new Reading(reading.colours(), reading.sizes(),
                    reading.note() == null ? missed : missed + " " + reading.note());
        }
        return reading;
    }

    // ---------------------------------------------------------------- the ask

    static String prompt(String productName, boolean part) {
        String context = productName == null || productName.isBlank() ? ""
                : "The product is called \"" + productName.trim() + "\" (for context only: do not put the brand or "
                + "product name in the shade names).\n";
        return "This picture shows " + (part ? "part of a display of " : "")
                + "many variants of one kind of cosmetics product placed together, mostly the same size and shape and "
                + "differing in colour (for example gel polish bottles, lipsticks, lash glue or eyeshadow pans).\n"
                + context
                + "List EVERY item you can see, one entry per item, including small ones at the back and ones cut off "
                + "at the edges of the picture. Look at the colour of each item carefully: on bottles it usually shows "
                + "on the cap or the top.\n"
                + "For each item give: \"name\", a short plain shade name of one to three words like a shop would write "
                + "(\"Ruby Red\", \"Baby Pink\", \"Rose Gold\"). Items that look exactly the same shade get exactly "
                + "the same name; items that differ even a little (light pink, baby pink, blush pink, salmon) get "
                + "DIFFERENT names, so every shade on the display can be told apart by its name. "
                + "\"hex\", the \"#RRGGBB\" of that item's own colour (the polish, the lipstick or the cap) as exactly "
                + "as you can, NOT of the background, table, glass, hand, reflections or label text; "
                + "\"code\", the shade number or series printed on that item if you can read one (like \"DH 03\", "
                + "\"KE32\", \"No. 12\"), else an empty string; "
                + "\"x\" and \"y\", the centre of the item in this picture, each from 0 to 1000, measured from the "
                + "left edge and from the top edge.\n"
                + "If a size or volume is printed on the packaging (like \"15ml\" or \"15g\"), list it once under "
                + "\"sizes\".\n"
                + "Do not invent items or codes you cannot see. If the picture shows no such products, return an empty "
                + "list. Answer with the JSON object only.";
    }

    static Map<String, Object> schema() {
        Map<String, Object> str = Map.of("type", "STRING");
        Map<String, Object> num = Map.of("type", "INTEGER");
        Map<String, Object> item = Map.of("type", "OBJECT",
                "properties", Map.of("name", str, "hex", str, "code", str, "count", num, "x", num, "y", num),
                "required", List.of("name", "hex"));
        return Map.of("type", "OBJECT",
                "properties", Map.of(
                        "colours", Map.of("type", "ARRAY", "items", item),
                        "sizes", Map.of("type", "ARRAY", "items", str),
                        "note", str),
                "required", List.of("colours"));
    }

    // ---------------------------------------------------------------- the answers, made safe and put together

    /** One item the AI listed, kept by its section, with its centre in whole-picture pixels. */
    private record Item(String name, String hex, String code, int count, double gx, double gy) {}

    /** A shade being built from items: the first item's name, dot and code, and how many items it has. */
    private static final class Shade {
        final String name;
        String hex;
        final String code;
        int count;
        final double gx;
        final double gy;

        Shade(Item i) {
            name = i.name();
            hex = i.hex();
            code = i.code();
            count = i.count();
            gx = i.gx();
            gy = i.gy();
        }
    }

    /**
     * Every section's answer (null for one that failed) put together: valid dots only, short names, each item once,
     * the same shade once with its count, in reading order (top to bottom, left to right). Never throws on an odd shape.
     */
    static Reading combine(List<PictureSections.Section> sections, List<JsonNode> answers) {
        List<Item> items = new ArrayList<>();
        Map<String, String> sizes = new LinkedHashMap<>();
        String note = null;
        int n = 0;
        for (int s = 0; s < sections.size(); s++) {
            JsonNode answer = answers.get(s);
            if (answer == null) {
                continue;
            }
            PictureSections.Section section = sections.get(s);
            for (JsonNode c : answer.path("colours")) {
                n++;
                Integer px = c.path("x").isNumber() ? c.path("x").asInt() : null;
                Integer py = c.path("y").isNumber() ? c.path("y").asInt() : null;
                if (!section.owns(px, py)) {
                    continue; // the neighbouring section keeps it
                }
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
                double[] at = section.centre(px, py);
                items.add(new Item(name, hex, code, count, at[0], at[1]));
            }
            for (JsonNode z : answer.path("sizes")) {
                String size = cut(ProductOptionsService.clean(z.asText(null)), 20);
                if (size != null && sizes.size() < MAX_SIZES) {
                    // "50ml", "50 ML" and "50 ml" are one size: the key ignores case and spaces, the first spelling is kept.
                    sizes.putIfAbsent(size.toLowerCase(Locale.ROOT).replace(" ", ""), size);
                }
            }
            if (note == null) {
                note = cut(ProductOptionsService.clean(answer.path("note").asText(null)), 200);
            }
        }
        if (sections.size() > 1) {
            // Reading order across the sections: bands of rows, then left to right.
            double band = sections.get(0).h() / 6.0;
            items.sort(Comparator.comparingLong((Item i) -> Math.round(i.gy() / Math.max(1, band)))
                    .thenComparingDouble(Item::gx));
        }

        List<Shade> shades = new ArrayList<>();
        for (Item item : items) {
            Shade same = shades.stream().filter(sh -> sameShade(sh, item)).findFirst().orElse(null);
            if (same != null) {
                same.count = Math.min(999, same.count + item.count());
                if (same.hex == null) {
                    same.hex = item.hex();
                }
            } else if (shades.size() < MAX_COLOURS) {
                shades.add(new Shade(item));
            }
        }

        // Two different shades the AI happened to give one name become "Baby Pink" and "Baby Pink 2".
        Map<String, Integer> seen = new HashMap<>();
        List<Found> found = new ArrayList<>();
        for (Shade sh : shades) {
            String key = sh.name.toLowerCase(Locale.ROOT);
            int k = seen.merge(key, 1, Integer::sum);
            String name = k == 1 ? sh.name : cut(sh.name, 36) + " " + k;
            ColorFamily family = sh.hex == null ? null : ColorFamily.fromHex(sh.hex);
            found.add(new Found(name, sh.hex, sh.code, sh.count, family == null ? null : family.name()));
        }
        return new Reading(found, new ArrayList<>(sizes.values()), note);
    }

    /** The same printed code, or the same name with dots close enough (or one unknown). */
    private static boolean sameShade(Shade shade, Item item) {
        if (shade.code != null && item.code() != null) {
            return key(shade.code).equals(key(item.code()));
        }
        if (!shade.name.equalsIgnoreCase(item.name())) {
            return false;
        }
        return shade.hex == null || item.hex() == null || distance(shade.hex, item.hex()) < SAME_SHADE;
    }

    private static String key(String code) {
        return code.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    /** CIE76 distance between two "#RRGGBB" dots. */
    static double distance(String a, String b) {
        double[] p = lab(a);
        double[] q = lab(b);
        return Math.sqrt(Math.pow(p[0] - q[0], 2) + Math.pow(p[1] - q[1], 2) + Math.pow(p[2] - q[2], 2));
    }

    private static double[] lab(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        double r = linear(((v >> 16) & 0xFF) / 255.0);
        double g = linear(((v >> 8) & 0xFF) / 255.0);
        double b = linear((v & 0xFF) / 255.0);
        double x = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047);
        double y = f(0.2126 * r + 0.7152 * g + 0.0722 * b);
        double z = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883);
        return new double[]{116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
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
