package com.nbh.backend.imagecleanup;

import java.util.ArrayList;
import java.util.List;

/**
 * How much text each description field may hold, sized for where a shopper reads it: the short line sits on a product
 * card, the longer text on the product page, the SEO pair in a search result. The database allows more (500 for the
 * short line and the SEO description, 200 for the title); these are the limits staff and the AI both work to.
 */
public final class DescriptionCaps {

    public static final int SHORT_DESCRIPTION = 140;
    public static final int DESCRIPTION = 700;
    public static final int HIGHLIGHTS_COUNT = 5;
    public static final int HIGHLIGHT = 80;
    public static final int INGREDIENTS = 400;
    public static final int HOW_TO_USE = 400;
    public static final int SEO_TITLE = 60;
    public static final int SEO_DESCRIPTION = 155;

    private DescriptionCaps() {}

    /** Trimmed, with runs of spaces collapsed and blank lines removed; null when nothing is left. */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        String collapsed = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    /** Cuts to {@code max} at the last word boundary (never mid-word), adding no ellipsis; for the AI's own output. */
    public static String fit(String text, int max) {
        String cleaned = clean(text);
        if (cleaned == null || cleaned.length() <= max) {
            return cleaned;
        }
        String cut = cleaned.substring(0, max);
        int space = cut.lastIndexOf(' ');
        if (space > max / 2) {
            cut = cut.substring(0, space);
        }
        return cut.replaceAll("[\\s,;:\\-]+$", "");
    }

    /** One highlight per line, at most {@link #HIGHLIGHTS_COUNT} of at most {@link #HIGHLIGHT} characters; null if none. */
    public static String fitHighlights(List<String> highlights) {
        if (highlights == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String h : highlights) {
            String fitted = fit(h == null ? null : h.replace('\n', ' '), HIGHLIGHT);
            if (fitted != null) {
                kept.add(fitted);
            }
            if (kept.size() == HIGHLIGHTS_COUNT) {
                break;
            }
        }
        return kept.isEmpty() ? null : String.join("\n", kept);
    }

    /** Splits stored highlights back into a list. */
    public static List<String> splitHighlights(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return stored.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Names the first field that is over its limit, or null if everything fits (used on text a person typed). */
    public static String firstTooLong(String shortDescription, String description, List<String> highlights,
                                      String ingredients, String howToUse, String seoTitle, String seoDescription) {
        if (len(shortDescription) > SHORT_DESCRIPTION) {
            return "The short description is over " + SHORT_DESCRIPTION + " characters.";
        }
        if (len(description) > DESCRIPTION) {
            return "The description is over " + DESCRIPTION + " characters.";
        }
        if (highlights != null && highlights.size() > HIGHLIGHTS_COUNT) {
            return "Use at most " + HIGHLIGHTS_COUNT + " highlights.";
        }
        if (highlights != null) {
            for (String h : highlights) {
                if (len(h) > HIGHLIGHT) {
                    return "A highlight is over " + HIGHLIGHT + " characters.";
                }
            }
        }
        if (len(ingredients) > INGREDIENTS) {
            return "Ingredients are over " + INGREDIENTS + " characters.";
        }
        if (len(howToUse) > HOW_TO_USE) {
            return "How to use is over " + HOW_TO_USE + " characters.";
        }
        if (len(seoTitle) > SEO_TITLE) {
            return "The search title is over " + SEO_TITLE + " characters.";
        }
        if (len(seoDescription) > SEO_DESCRIPTION) {
            return "The search description is over " + SEO_DESCRIPTION + " characters.";
        }
        return null;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}
