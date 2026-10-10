package com.nbh.backend.variantgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.nbh.backend.imagecleanup.GeminiTextClient;
import com.nbh.backend.variantgen.VariantCombos.Combo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Asks Gemini (text) the two things only language can answer: which colours and sizes the staff meant when they wrote
 * them in their own words (only when they left both lists empty), and for each option a swatch colour and a short
 * description. The product's own description and the staff's prompt are the only facts it may use.
 */
@Service
public class VariantPlanner {

    public record Axes(List<String> colors, List<String> sizes) {}

    /** What the planner knows about each option. Either field may be null if Gemini gave nothing usable. */
    public record Details(String swatchHex, String description) {}

    private static final int MAX_DESCRIPTION = 400;

    private final GeminiTextClient gemini;

    public VariantPlanner(GeminiTextClient gemini) {
        this.gemini = gemini;
    }

    /** Colours and sizes named in the staff's prompt or in the product's text — never invented. */
    public Axes extractAxes(String productName, String baseOption, String productDescription, String note) {
        Map<String, Object> str = Map.of("type", "STRING");
        Map<String, Object> list = Map.of("type", "ARRAY", "items", str);
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of("colors", list, "sizes", list), "required", List.of("colors", "sizes"));
        String prompt = "A shop is adding variants of the product \"" + productName + "\"" + optionPart(baseOption)
                + ". Read the shop's prompt"
                + (blank(productDescription) ? "" : " and the product's description")
                + " and list the colours and the sizes the shop wants variants for.\n"
                + "RULES: list only colours/sizes that are written there as options to create; never invent or guess "
                + "one; do not list the product's current colour/size unless it is also named as a new option; give "
                + "each colour as a plain name such as \"Emerald Green\"; sizes as written (\"500ml\", \"Large\"). "
                + "At most " + VariantCombos.MAX_VARIANTS + " of each. Use an empty list when none are named.\n\n"
                + "Shop's prompt: \"" + clip(note, 1500) + "\"\n"
                + (blank(productDescription) ? "" : "Product description: \"" + clip(productDescription, 1500) + "\"\n")
                + "Answer with the JSON object only.";
        JsonNode a = gemini.ask(null, prompt, schema);
        return new Axes(strings(a.path("colors")), strings(a.path("sizes")));
    }

    /** One {@link Details} per combo, in the same order. */
    public List<Details> details(String productName, String productDescription, String note, List<Combo> combos) {
        Map<String, Object> str = Map.of("type", "STRING");
        Map<String, Object> item = Map.of("type", "OBJECT",
                "properties", Map.of("index", Map.of("type", "INTEGER"), "swatchHex", str, "description", str),
                "required", List.of("index", "swatchHex", "description"));
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of("variants", Map.of("type", "ARRAY", "items", item)),
                "required", List.of("variants"));
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < combos.size(); i++) {
            Combo c = combos.get(i);
            list.append(i).append(": ")
                    .append(c.color() != null ? "colour \"" + c.color() + "\"" : "")
                    .append(c.color() != null && c.size() != null ? ", " : "")
                    .append(c.size() != null ? "size \"" + c.size() + "\"" : "").append('\n');
        }
        String prompt = "You write variant text for Trendysh, a beauty and home-supplies shop in Nairobi. The product is \""
                + productName + "\"." + (blank(productDescription) ? "" : " Its description: \"" + clip(productDescription, 1500) + "\".")
                + "\n\nFor each numbered option below return:\n"
                + "- index: the number.\n"
                + "- swatchHex: for an option with a colour, the \"#RRGGBB\" that best shows that colour as a small dot; "
                + "otherwise an empty string.\n"
                + "- description: one or two plain sentences (at most " + MAX_DESCRIPTION + " characters) for that "
                + "option, saying which colour and/or size it is. Use only facts from the product description and the "
                + "shop's prompt; never invent materials, sizes, claims or features. No emojis, no hype, no price.\n\n"
                + "Options:\n" + list
                + (blank(note) ? "" : "\nShop's prompt (treat as true): \"" + clip(note, 1500) + "\"\n")
                + "\nAnswer with the JSON object only.";
        JsonNode a = gemini.ask(null, prompt, schema);
        Details[] out = new Details[combos.size()];
        for (JsonNode v : a.path("variants")) {
            int i = v.path("index").asInt(-1);
            if (i >= 0 && i < out.length && out[i] == null) {
                String d = v.path("description").asText("").replaceAll("\\s+", " ").trim();
                if (d.length() > MAX_DESCRIPTION) {
                    d = d.substring(0, MAX_DESCRIPTION).trim();
                }
                out[i] = new Details(VariantCombos.normaliseHex(v.path("swatchHex").asText(null)), d.isEmpty() ? null : d);
            }
        }
        List<Details> result = new ArrayList<>();
        for (Details d : out) {
            result.add(d == null ? new Details(null, null) : d);
        }
        return result;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText("")));
        return VariantCombos.clean(out);
    }

    private static String optionPart(String baseOption) {
        return blank(baseOption) ? "" : " (its current option is \"" + baseOption + "\")";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clip(String s, int max) {
        String plain = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return plain.length() > max ? plain.substring(0, max) : plain;
    }
}
