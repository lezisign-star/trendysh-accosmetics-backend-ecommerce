package com.nbh.backend.banners;

import java.util.List;

/**
 * What Gemini is asked for a builder banner's picture (owner, 2026-10-31): a picture shaped for where the banner shows,
 * starting from the featured products' real photos, with the part where the shop draws the words left calm, and no words
 * of its own. The headline, offer and button are drawn by the shop over it, so they stay sharp and can be changed.
 */
final class PromoBannerPrompts {

    private PromoBannerPrompts() {
    }

    /** The picture's shape for Gemini, closest to how the placement is drawn. */
    static String aspectRatio(PromoBanner.Placement placement) {
        return switch (placement) {
            case HERO -> "16:9";
            case STRIP -> "21:9";
            case TILE -> "4:5";
            case OFFER_CARD, GRID_TILE -> "2:3";
            case CIRCLE -> "1:1";
        };
    }

    /** Where the shop draws the words, so the picture keeps that part plain. */
    static String layout(PromoBanner.Placement placement) {
        return switch (placement) {
            case HERO -> "A wide home-page hero banner. Put the main subject in the right half. Keep the LEFT 45% of the"
                    + " picture calm and uncluttered (soft background, gentle gradient or blurred set) because a headline,"
                    + " an offer and a button are drawn there.";
            case STRIP -> "A very wide, short promotional band. Put the subject toward the right third. Keep the left"
                    + " two-thirds calm and plain because a line of text and a button are drawn there.";
            case TILE, OFFER_CARD, GRID_TILE -> "A tall advert tile. Put the subject in the upper part. Keep the BOTTOM 40%"
                    + " calm and plain because a headline and a button are drawn there.";
            case CIRCLE -> "A small round category icon. Centre the subject and keep it well inside the middle, with a"
                    + " soft plain background and nothing important near the edges, because it is cropped to a circle.";
        };
    }

    /**
     * The whole instruction. {@code products} names what the attached photos show (empty when there are none);
     * {@code brief} is what staff asked for; {@code theme} the occasion, if any.
     */
    static String picture(PromoBanner.Placement placement, List<String> products, String brief, String theme) {
        StringBuilder p = new StringBuilder();
        p.append("Create a polished, professional e-commerce advertising picture for Trendysh Accosmetic, a beauty and")
                .append(" cosmetics shop in Nairobi, Kenya. It must look like a premium online-marketplace campaign banner.\n");
        p.append(layout(placement)).append('\n');
        if (!products.isEmpty()) {
            p.append("The attached photos are the real products: ").append(String.join("; ", products))
                    .append(". Show these exact products, recognisable, with their true packaging, shapes and colours;")
                    .append(" do not invent other brands or change their labels.\n");
        }
        if (brief != null && !brief.isBlank()) {
            p.append("Staff asked for: ").append(brief.strip()).append('\n');
        } else {
            p.append("Style: a bright, aspirational lifestyle scene with the products as the hero, clean studio-quality")
                    .append(" lighting.\n");
        }
        if (theme != null && !theme.isBlank()) {
            p.append("The campaign is for ").append(theme).append("; let its mood show in the colours and setting.\n");
        }
        p.append("People, if any, are Kenyan or East African, natural and confident.\n");
        p.append("Do NOT put any words, letters, numbers, prices, logos, watermarks or badges in the picture.");
        return p.toString();
    }
}
