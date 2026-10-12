package com.nbh.backend.variantjobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.nbh.backend.catalog.entity.ColorFamily;
import com.nbh.backend.catalog.service.ProductOptionsService;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiTextClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import com.nbh.backend.variantgen.VariantCombos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * A dot and colour family for each colour name staff type in the wizard (VARIANTS_PLAN.md 3.4). Names on the built-in
 * list of everyday cosmetics shades are answered at once and free; only the rest go to Gemini, in one call. Staff always
 * see the suggestion and can change it, so a wrong guess is a nudge, not a decision.
 */
@Slf4j
@Service
public class SwatchSuggester {

    public enum Source { LIST, AI, NONE }

    public record Suggestion(String name, String hex, String colorFamily, Source source) {}

    /** Longest phrases first so "hot pink" wins over "pink". Family only where the dot can't show it. */
    private record Known(String hex, ColorFamily family) {}

    private static final Map<String, Known> KNOWN = new LinkedHashMap<>();

    static {
        add("clear", "#EFE8DC", ColorFamily.CLEAR);
        add("transparent", "#EFE8DC", ColorFamily.CLEAR);
        add("top coat", "#EFE8DC", ColorFamily.CLEAR);
        add("multicolour", "#C060A0", ColorFamily.MULTI);
        add("multicolor", "#C060A0", ColorFamily.MULTI);
        add("rainbow", "#C060A0", ColorFamily.MULTI);
        add("silver", "#C0C0C0", ColorFamily.SILVER);
        add("chrome", "#C0C0C0", ColorFamily.SILVER);
        add("rose gold", "#B76E79", ColorFamily.GOLD);
        add("gold", "#C9A24A", ColorFamily.GOLD);
        add("bronze", "#A97142", null);
        add("copper", "#B87333", null);
        add("hot pink", "#E0287A", null);
        add("baby pink", "#F4C2C2", null);
        add("blush", "#E9A3B2", null);
        add("rose", "#E8A0A8", null);
        add("pink", "#E06A8C", null);
        add("fuchsia", "#D0208C", null);
        add("magenta", "#C2185B", null);
        add("ruby", "#9B111E", null);
        add("cherry", "#C21E3A", null);
        add("wine", "#6B1630", null);
        add("burgundy", "#800020", null);
        add("maroon", "#6D1A2A", null);
        add("crimson", "#B0102E", null);
        add("scarlet", "#D0201E", null);
        add("red", "#B3122E", null);
        add("coral", "#F2705E", null);
        add("peach", "#F6B48F", null);
        add("salmon", "#F08A75", null);
        add("orange", "#F28500", null);
        add("tangerine", "#F28500", null);
        add("nude", "#C8A28A", null);
        add("beige", "#D8BFA0", null);
        add("champagne", "#D9C29A", null);
        add("ivory", "#F2E8D5", null);
        add("cream", "#F3E5C8", null);
        add("caramel", "#B07840", null);
        add("honey", "#C68E4E", null);
        add("tan", "#C49A6C", null);
        add("mocha", "#7B4B3A", null);
        add("coffee", "#6F4E37", null);
        add("espresso", "#4A2E25", null);
        add("chocolate", "#5A3222", null);
        add("brown", "#7B4B3A", null);
        add("plum", "#6E2A4D", null);
        add("berry", "#7A1F4A", null);
        add("lilac", "#B49ACB", null);
        add("lavender", "#B9A3D6", null);
        add("violet", "#7F3FBF", null);
        add("purple", "#6E2A6D", null);
        add("navy", "#1F2A5A", null);
        add("royal blue", "#2A52BE", null);
        add("sky blue", "#87CEEB", null);
        add("teal", "#008080", null);
        add("turquoise", "#30C0B8", null);
        add("blue", "#2E5EAA", null);
        add("emerald", "#2E8B57", null);
        add("mint", "#98E0B8", null);
        add("olive", "#708238", null);
        add("sage", "#9CAF88", null);
        add("green", "#2E8B57", null);
        add("lemon", "#F4E04D", null);
        add("mustard", "#D4A017", null);
        add("yellow", "#F4D03F", null);
        add("white", "#FFFFFF", null);
        add("snow", "#FFFFFF", null);
        add("black", "#1B1718", null);
        add("jet", "#1B1718", null);
        add("charcoal", "#36454F", null);
        add("grey", "#808080", null);
        add("gray", "#808080", null);
    }

    private static void add(String name, String hex, ColorFamily family) {
        KNOWN.put(name, new Known(hex, family));
    }

    private final GeminiTextClient gemini;
    private final ImageCleanupConfig config;

    public SwatchSuggester(GeminiTextClient gemini, ImageCleanupConfig config) {
        this.gemini = gemini;
        this.config = config;
    }

    public List<Suggestion> suggest(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        if (names.size() > 200) {
            throw new IllegalArgumentException("At most 200 colours at a time.");
        }
        List<Suggestion> out = new ArrayList<>(Collections.nCopies(names.size(), null));
        List<Integer> unknown = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = ProductOptionsService.clean(names.get(i));
            if (name == null) {
                out.set(i, new Suggestion(names.get(i), null, null, Source.NONE));
                continue;
            }
            Known k = fromList(name);
            if (k != null) {
                out.set(i, suggestion(name, k.hex(), k.family(), Source.LIST));
            } else {
                unknown.add(i);
            }
        }
        if (!unknown.isEmpty()) {
            Map<Integer, String> ai = askAi(unknown.stream().map(i -> ProductOptionsService.clean(names.get(i))).toList());
            for (int j = 0; j < unknown.size(); j++) {
                int i = unknown.get(j);
                String name = ProductOptionsService.clean(names.get(i));
                String hex = ai.get(j);
                out.set(i, hex == null ? new Suggestion(name, null, null, Source.NONE) : suggestion(name, hex, null, Source.AI));
            }
        }
        return out;
    }

    /** A shade the built-in list knows, with its dot and family. */
    public record Shade(String hex, ColorFamily family) {}

    /** The built-in list's dot and family for a colour name ("Deep Ruby Red" → ruby), or null when it knows none. */
    public static Shade knownShade(String name) {
        Known k = name == null ? null : fromList(name);
        if (k == null) {
            return null;
        }
        return new Shade(k.hex(), k.family() != null ? k.family() : ColorFamily.fromHex(k.hex()));
    }

    /** The longest known shade word in the name ("Deep Ruby Red" → "ruby"), as a whole word. */
    static Known fromList(String name) {
        String n = " " + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim() + " ";
        Known best = null;
        int bestLength = 0;
        for (Map.Entry<String, Known> e : KNOWN.entrySet()) {
            if (n.contains(" " + e.getKey() + " ") && e.getKey().length() > bestLength) {
                best = e.getValue();
                bestLength = e.getKey().length();
            }
        }
        return best;
    }

    private static Suggestion suggestion(String name, String hex, ColorFamily family, Source source) {
        ColorFamily f = family != null ? family : ColorFamily.fromHex(hex);
        return new Suggestion(name, hex, f == null ? null : f.name(), source);
    }

    /** Index into {@code names} → "#RRGGBB", for the names Gemini could place. Empty when it isn't set up or fails. */
    private Map<Integer, String> askAi(List<String> names) {
        if (!config.geminiConfigured()) {
            return Map.of();
        }
        Map<String, Object> item = Map.of("type", "OBJECT",
                "properties", Map.of("index", Map.of("type", "INTEGER"), "hex", Map.of("type", "STRING")),
                "required", List.of("index", "hex"));
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of("colors", Map.of("type", "ARRAY", "items", item)), "required", List.of("colors"));
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            list.append(i).append(": \"").append(names.get(i)).append("\"\n");
        }
        String prompt = "These are shade names a cosmetics shop uses for product colours. For each, give the \"#RRGGBB\" "
                + "that best shows that shade as a small colour dot. If a name says nothing about a colour, give an empty "
                + "string.\n\n" + list + "\nAnswer with the JSON object only.";
        try {
            JsonNode a = gemini.ask(null, prompt, schema);
            Map<Integer, String> out = new HashMap<>();
            for (JsonNode c : a.path("colors")) {
                int i = c.path("index").asInt(-1);
                String hex = VariantCombos.normaliseHex(c.path("hex").asText(null));
                if (i >= 0 && i < names.size() && hex != null) {
                    out.putIfAbsent(i, hex);
                }
            }
            return out;
        } catch (GeminiImageClient.GeminiException e) {
            log.info("Swatch suggestions from Gemini failed: {}", e.getMessage());
            return Map.of();
        }
    }
}
