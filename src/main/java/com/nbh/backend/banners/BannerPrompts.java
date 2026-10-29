package com.nbh.backend.banners;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What Gemini is asked for a banner (owner, 2026-10-29): billboard-style pictures (families, happy people, cartoon
 * jokes, funny animals, the product with its offer, greetings) with no words in them, drawn for a container whose word
 * area is left calm; one short line in the shop's voice; and a look at the finished picture to see where the words fit.
 */
final class BannerPrompts {

    private BannerPrompts() {
    }

    /** Which third of a card is kept calm for the words. */
    enum Calm { TOP, BOTTOM }

    static String scene(Banner.Style style) {
        return switch (style) {
            case FAMILY -> "a warm, joyful Kenyan family at home (parents and children, natural and candid), one of them"
                    + " using or holding the product, everyone smiling";
            case HAPPY_PEOPLE -> "a confident, happy young Kenyan woman or man at a bright salon or at home with the product,"
                    + " a big natural smile, lifestyle-advert look";
            case CARTOON_JOKE -> "a playful flat cartoon illustration: a cheeky cartoon character with a funny, exaggerated"
                    + " reaction to the product (wide eyes, a wink), bold friendly colours, like a fun supermarket billboard";
            case ANIMAL -> "a funny, charming animal from Kenya in a cartoon or 3D style (a giraffe with long lashes, a zebra"
                    + " getting a manicure, a lion with a fresh hairdo) next to the product, light-hearted and cute";
            case PRODUCT_OFFER -> "the product as the hero on a styled set, large and sharp, like a premium shop poster";
            case GREETING -> "a festive greeting scene for the moment, the product placed naturally in it, warm and inviting";
        };
    }

    /** The brief: the scene to draw and the one line, in the shop's voice. */
    static String brief(Occasion o, Banner.Style style, String subject, boolean offerOn) {
        return "You write for Trendysh Accosmetic, a beauty-supply shop with three shops in Nairobi (nails, lashes, brows,"
                + " makeup, spa and salon supplies, retail and wholesale). Occasion: " + o.getName() + ". " + o.getTone()
                + " The banner is about: " + subject + ". Style: " + scene(style) + "."
                + (offerOn ? " There is a real offer on now; the shop writes the offer itself, so do not mention it."
                        : " There is no offer to mention.")
                + " Give a vivid one-paragraph description of the picture to draw (setting, people or characters, the"
                + " product's place, colours of the occasion), and one short line for the banner: at most 48 characters,"
                + " English with a light Kenyan Sheng touch where it lands naturally (e.g. \"Weekend iko sawa\", \"Sasa"
                + " glow!\"), fun or warm to suit the style, never rude, no hashtags or emojis, and NO numbers, prices,"
                + " percentages, dates or brand names.";
    }

    static Map<String, Object> briefSchema() {
        return Map.of("type", "OBJECT",
                "properties", Map.of(
                        "scene", Map.of("type", "STRING"),
                        "line", Map.of("type", "STRING")),
                "required", List.of("scene", "line"));
    }

    /** The picture: the real product (or the category's photo) edited into the scene, for a card or a circle. */
    static String picture(Occasion o, String scene, String subject, boolean circle, Calm calm) {
        String shape = circle
                ? "Square 1:1, the subject centred with a little room around it, because it is shown inside a circle."
                : "Portrait 2:3. Keep the " + calm.name().toLowerCase(Locale.ROOT) + " third of the picture calm and"
                        + " plain (a soft background, sky, wall or out-of-focus space, with no faces, products or detail in"
                        + " it), because words are written there later.";
        return "The photo shows a real product sold by a beauty-supply shop in Nairobi (" + subject + "). Make a"
                + " promotional billboard picture for the shop's " + o.getName() + " (" + o.getTone() + "): " + scene
                + ". Keep THIS product exactly as in the photo: the same shape, colours, packaging and label; do not"
                + " invent other products. People look Kenyan and natural; no real celebrities, no other brands' mascots"
                + " or logos. " + shape + " Absolutely no text, letters, numbers, prices, badges, logos or watermarks"
                + " anywhere in the picture.";
    }

    /** The fit check: Gemini looks at the finished picture and says where words would fit. */
    static String fit() {
        return "Look at this promotional picture. Words will be written over one third of it later. Answer: calmArea"
                + " (\"top\", \"bottom\" or \"none\"): which third (top or bottom) is plain enough for words, or none;"
                + " busyness: how busy that third is, 0 (plain) to 10 (very busy); hasText: true if the picture contains"
                + " any letters, numbers or logos; productVisible: true if a beauty product is clearly visible; mood: up"
                + " to three words; mainColor: the main colour as #RRGGBB.";
    }

    static Map<String, Object> fitSchema() {
        return Map.of("type", "OBJECT",
                "properties", Map.of(
                        "calmArea", Map.of("type", "STRING", "enum", List.of("top", "bottom", "none")),
                        "busyness", Map.of("type", "INTEGER"),
                        "hasText", Map.of("type", "BOOLEAN"),
                        "productVisible", Map.of("type", "BOOLEAN"),
                        "mood", Map.of("type", "STRING"),
                        "mainColor", Map.of("type", "STRING")),
                "required", List.of("calmArea", "busyness", "hasText", "productVisible"));
    }

    /**
     * The container a card picture fits, from the fit check: words at the top or bottom where it is calm, over a
     * darkened lower half when it is only a little busy; null when it fits none (the picture is dropped).
     */
    static String container(String calmArea, int busyness, boolean hasText) {
        if (hasText) return null;
        if (busyness <= 4 && "top".equals(calmArea)) return "card-words-top";
        if (busyness <= 4 && "bottom".equals(calmArea)) return "card-words-bottom";
        if (busyness <= 7) return "card-words-over";
        return null;
    }

    /** The one line, kept honest and short: no numbers, prices or percentages (the shop writes offers itself). */
    static String cleanLine(String raw, String fallback) {
        if (raw == null) return fallback;
        String t = raw.replaceAll("[\"“”#]", "").replaceAll("\\s+", " ").strip();
        if (t.isEmpty() || t.length() > 60 || t.matches(".*(\\d|%|(?i)ksh|(?i)kes|(?i)shilling).*")) return fallback;
        return t;
    }
}
