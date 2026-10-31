package com.nbh.backend.banners;

import java.util.List;
import java.util.Locale;

/**
 * What Gemini is asked for a builder banner's picture (owner, 2026-10-31; made "salesy" the same day after the first
 * pictures looked like lifestyle photos that sold nothing). The instruction stands on its own, modelled on the big
 * marketplaces' campaign tiles: the shop's real products as the hero on podiums against one soft colour, sales props,
 * studio light, and clear space where the shop draws the headline and button. What staff type is only a mood hint,
 * cleaned of anything that would fight the rules ({@link #moodHint}). The picture never has words of its own: the shop
 * draws them over it, sharp and changeable.
 */
final class PromoBannerPrompts {

    private PromoBannerPrompts() {
    }

    /** The picture's shape for Gemini, closest to how the placement is drawn. */
    static String aspectRatio(PromoBanner.Placement placement) {
        return switch (placement) {
            case HERO -> "21:9";
            case STRIP -> "21:9";
            case TILE -> "5:4";
            case OFFER_CARD, GRID_TILE -> "3:4";
            case CIRCLE -> "1:1";
        };
    }

    /** Where the products go and which parts stay clear, matching where the shop draws the words. */
    static String layout(PromoBanner.Placement placement) {
        return switch (placement) {
            case HERO, STRIP -> "A wide, short web banner. Arrange the products as a tight group in the RIGHT 45% of"
                    + " the frame, large and sharp. The LEFT 55% must be a clean, smooth continuation of the backdrop"
                    + " with nothing in it, because the headline, offer and button are drawn there.";
            case TILE -> "A square-ish promo tile. Put the products in the MIDDLE of the frame, filling about half of it."
                    + " Keep the TOP quarter completely clear (plain backdrop) for a bold headline, and the BOTTOM fifth"
                    + " clear for a pill-shaped button.";
            case OFFER_CARD, GRID_TILE -> "A tall product-grid card. Put the products in the upper-middle of the frame,"
                    + " filling about half of it. Keep the BOTTOM 35% clear and plain because the offer, headline and"
                    + " button are drawn there.";
            case CIRCLE -> "A round category icon. One product (or a tight pair) centred and filling about 70% of the"
                    + " frame, on a plain soft backdrop, nothing near the edges because it is cropped to a circle.";
        };
    }

    /** Different looks for the pictures of one "Generate", so staff have a real choice. */
    private static final List<String> LOOKS = List.of(
            "PODIUM STUDIO: the products standing on two or three smooth cylindrical podiums of different heights,"
                    + " in front of a softly curved wall (an arch or a wave line across the backdrop), like a premium"
                    + " marketplace campaign tile.",
            "HERO SPOTLIGHT: the products on a single glossy pedestal with a soft spotlight glow behind them and a few"
                    + " floating accents around them (petals, sparkles, glossy droplets), energetic and premium.",
            "FLAT-LAY SALE: a styled top-down flat-lay of the products neatly arranged with beauty props (brushes, nail"
                    + " polish bottles, cotton pads, flower petals) on a smooth single-colour surface, bright and tidy.");

    static String look(int variant) {
        return LOOKS.get(Math.floorMod(variant, LOOKS.size()));
    }

    /**
     * Staff's words, as a mood hint only: one line, at most 160 characters, with anything asking for text, logos,
     * prices or other rules taken out (the instruction already says what the picture must and must not have).
     */
    static String moodHint(String brief) {
        if (brief == null) return null;
        String t = brief.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[\"“”`]", "").replaceAll("\\s+", " ").strip();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String banned : List.of("ignore", "instruction", "prompt", "write ", "text", "word", "letter", "logo",
                "watermark", "price", "caption", "headline", "slogan", "say ")) {
            if (lower.contains(banned)) {
                // keep only the part before the first rule-like phrase: the mood words staff usually lead with
                int at = lower.indexOf(banned);
                t = t.substring(0, at).strip();
                lower = t.toLowerCase(Locale.ROOT);
            }
        }
        t = t.replaceAll("[,.;:!\\-–]+$", "").strip();
        if (t.length() > 160) t = t.substring(0, 160).strip();
        return t.isBlank() ? null : t;
    }

    /** The answer shape for {@link #words}: three suggestions, each a headline, a sub-line and a button. */
    static java.util.Map<String, Object> wordsSchema() {
        java.util.Map<String, Object> str = java.util.Map.of("type", "STRING");
        java.util.Map<String, Object> one = java.util.Map.of("type", "OBJECT",
                "properties", java.util.Map.of("headline", str, "subline", str, "cta", str),
                "required", List.of("headline", "subline", "cta"));
        return java.util.Map.of("type", "OBJECT",
                "properties", java.util.Map.of("suggestions", java.util.Map.of("type", "ARRAY", "items", one)),
                "required", List.of("suggestions"));
    }

    /**
     * Sales words for a banner (owner, 2026-10-31): short, punchy, marketplace-style, in the shop's Nairobi voice, from
     * what the banner sells. {@code products} are "name — KSh price (deal KSh x)"; {@code hint} is staff's optional topic.
     */
    static String words(PromoBanner.Placement placement, List<String> products, String offer, String theme,
                        String hint) {
        int headlineMax = placement == PromoBanner.Placement.HERO || placement == PromoBanner.Placement.STRIP ? 38 : 26;
        StringBuilder p = new StringBuilder();
        p.append("You write the words on promotional banners for Trendysh Accosmetic, a beauty-supplies shop in")
                .append(" Nairobi selling to salons and beauty lovers (nails, lashes, brows, makeup, skin care, tools).")
                .append(" Write like the best online marketplace sale banners: short, punchy, benefit-led and urgent.\n");
        p.append("Banner place: ").append(switch (placement) {
            case HERO -> "the big home-page banner";
            case STRIP -> "a wide campaign strip";
            case TILE -> "a promo tile among products";
            case OFFER_CARD -> "the first card of the 'On offer today' row";
            case GRID_TILE -> "a tile in the product grid";
            case CIRCLE -> "a category circle";
        }).append(".\n");
        if (!products.isEmpty()) p.append("It sells: ").append(String.join("; ", products)).append(".\n");
        if (offer != null && !offer.isBlank()) p.append("Its offer badge already says: ").append(offer).append(".\n");
        if (theme != null && !theme.isBlank()) p.append("Occasion: ").append(theme).append(".\n");
        String topic = moodHint(hint);
        if (topic != null) p.append("The shop's idea for it (a starting point, not words to copy): ").append(topic).append(".\n");
        p.append("Give 3 different suggestions. Each has:\n")
                .append("- headline: at most ").append(headlineMax).append(" characters, the strongest selling idea")
                .append(" (e.g. 'Salon-ready nails for less', 'Lash restock week'); may end with ONE fitting emoji.\n")
                .append("- subline: at most 70 characters, one concrete reason to buy now (delivery across Nairobi,")
                .append(" wholesale prices, new stock, the offer); no emoji.\n")
                .append("- cta: at most 14 characters, an action ('Shop now', 'Grab the deal', 'Restock now').\n")
                .append("Use simple English; a light Kenyan touch (a Sheng or Swahili word) is welcome in at most one")
                .append(" suggestion. Do not repeat the offer badge's words, do not invent prices or discounts, no")
                .append(" ALL CAPS, no hashtags, no quotation marks.");
        return p.toString();
    }

    /**
     * The whole instruction. {@code products} names what the attached photos show (empty when there are none);
     * {@code brief} is what staff typed (a mood hint at most); {@code theme} the occasion and its colour, if any;
     * {@code variant} picks one of the looks so the pictures of one request differ.
     */
    static String picture(PromoBanner.Placement placement, List<String> products, String brief, String theme,
                          String themeColor, int variant) {
        StringBuilder p = new StringBuilder();
        p.append("You are a senior e-commerce art director. Create a high-converting promotional campaign picture for")
                .append(" Trendysh Accosmetic, a beauty-supplies shop in Nairobi (nails, lashes, brows, makeup, skin")
                .append(" care, salon tools). It must look like a top online marketplace's sale banner: clean, bright,")
                .append(" premium, and obviously SELLING the products.\n\n");

        p.append("PRODUCTS: ");
        if (!products.isEmpty()) {
            p.append("the attached photos are the real products: ").append(String.join("; ", products))
                    .append(". They are the hero of the picture: show these exact products, large, sharp and")
                    .append(" recognisable, with their true packaging, shapes, colours and labels as in the photos.")
                    .append(" Do not invent other brands, do not change or blur their labels, do not add other products")
                    .append(" that compete with them. Clean any messy background from the photos away.\n");
        } else {
            p.append("the attached photo shows the kind of product sold; make it the hero, large and sharp.\n");
        }

        p.append("\nLAYOUT: ").append(layout(placement)).append('\n');
        p.append("\nLOOK: ").append(look(variant)).append('\n');

        p.append("\nCOLOUR: one soft, bright, single-colour pastel backdrop");
        if (themeColor != null && !themeColor.isBlank()) {
            p.append(" in a light tint of ").append(themeColor).append(" (the campaign colour)");
        } else {
            p.append(" chosen to flatter the products (soft pink, lilac, mint, peach or warm cream)");
        }
        p.append(", with a slightly deeper shade of the same colour for the podiums or surface. Simple, uncluttered,")
                .append(" high contrast between the products and the backdrop.\n");

        p.append("\nLIGHT AND FINISH: professional studio product photography, soft key light with gentle shadows")
                .append(" and subtle reflections, crisp focus on the products, polished 3D-render-like quality,")
                .append(" vibrant but natural colours.\n");

        if (theme != null && !theme.isBlank()) {
            p.append("\nOCCASION: the campaign is for ").append(theme)
                    .append("; add a few small props that suggest it, never covering the products.\n");
        }
        String hint = moodHint(brief);
        if (hint != null) {
            p.append("\nMOOD HINT from the shop (use only if it fits everything above): ").append(hint).append('\n');
        }

        p.append("\nRULES: no words, letters, numbers, prices, logos other than those already printed on the")
                .append(" products, no badges, no watermarks, no frames or borders. No people's faces; hands or a")
                .append(" partial model are fine only in the flat-lay or spotlight looks. Keep the clear areas")
                .append(" described in LAYOUT truly empty.");
        return p.toString();
    }
}
