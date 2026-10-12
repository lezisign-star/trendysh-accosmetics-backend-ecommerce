package com.nbh.backend.catalog.entity;

import java.util.Locale;

/**
 * The colour groups the shop filters by, so "Red" finds Ruby Red, Cherry and Wine (VARIANTS_PLAN.md 3.1). A variant's
 * family is staff's choice when they made one, else worked out from its dot by {@link #fromHex}. Stored and sent by
 * name ("RED"); {@link #label()} is how it reads.
 */
public enum ColorFamily {
    RED, PINK, CORAL, ORANGE, NUDE, BROWN, PURPLE, BLUE, GREEN, YELLOW, GOLD, SILVER, WHITE, BLACK, GREY, CLEAR, MULTI;

    /** "Red", "Grey". */
    public String label() {
        String n = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /**
     * Staff's typed or picked family, any case, "Red" or "RED". Null/blank = none chosen. Anything else is refused,
     * so a typo cannot quietly drop a variant out of every colour filter.
     */
    public static ColorFamily parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + raw.trim() + "\" is not a colour family");
        }
    }

    /** The family stored as text, or null when it is none or not one this version knows. */
    public static ColorFamily ofStored(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The family a dot looks like, or null when there is no valid "#RRGGBB" dot. Hue/saturation/lightness buckets, tuned
     * on real cosmetics shades: dark reds and wines stay red, light reds read as pink, bright light orange-reds as coral,
     * dark warm shades as brown, pale warm shades as nude, mid warm yellows as gold. CLEAR, SILVER and MULTI can't be
     * seen in a dot; only staff choose those.
     */
    public static ColorFamily fromHex(String hex) {
        if (hex == null) {
            return null;
        }
        String h = hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (h.length() == 8) {
            h = h.substring(0, 6); // ignore an alpha pair
        }
        if (!h.matches("(?i)[0-9a-f]{6}")) {
            return null;
        }
        double r = Integer.parseInt(h.substring(0, 2), 16) / 255.0;
        double g = Integer.parseInt(h.substring(2, 4), 16) / 255.0;
        double b = Integer.parseInt(h.substring(4, 6), 16) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double light = (max + min) / 2;
        double delta = max - min;
        double sat = delta == 0 ? 0 : delta / (1 - Math.abs(2 * light - 1));
        double hue;
        if (delta == 0) {
            hue = 0;
        } else if (max == r) {
            hue = 60 * (((g - b) / delta) % 6);
        } else if (max == g) {
            hue = 60 * (((b - r) / delta) + 2);
        } else {
            hue = 60 * (((r - g) / delta) + 4);
        }
        if (hue < 0) {
            hue += 360;
        }

        if (light <= 0.12) {
            return BLACK;
        }
        if (sat <= 0.10) {
            return light >= 0.92 ? WHITE : GREY;
        }
        if (light >= 0.94) {
            return WHITE;
        }
        if (hue >= 345 || hue < 15) {
            if (light >= 0.70) return PINK;
            if (hue < 15 && light >= 0.55 && sat >= 0.6) return CORAL;
            if (hue < 15 && light < 0.45 && sat < 0.6) return BROWN;
            return RED;
        }
        if (hue < 45) {
            if (light < 0.45) return BROWN;
            if (hue >= 38 && sat >= 0.35 && light < 0.65) return GOLD;
            if (sat < 0.6 && light >= 0.55) return NUDE;
            if (hue < 25 && sat >= 0.6) return CORAL;
            return ORANGE;
        }
        if (hue < 70) {
            if (light < 0.35) return BROWN;
            if (sat >= 0.35 && light < 0.6) return GOLD;
            return YELLOW;
        }
        if (hue < 170) return GREEN;
        if (hue < 260) return BLUE;
        if (hue < 330) return PURPLE;
        return light < 0.4 ? RED : PINK;
    }
}
