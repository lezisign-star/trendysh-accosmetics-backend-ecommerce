package com.nbh.backend.variantgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the colours and sizes staff gave into the options to create: colours alone, sizes alone, or every colour in every
 * size. Names are cleaned and de-duplicated ignoring case; the total is capped so a typo cannot start hundreds of photos.
 */
public final class VariantCombos {

    public static final int MAX_VARIANTS = 150;

    /** One option to create; either part may be null (a colour-only or size-only variant). */
    public record Combo(String color, String size) {
        /** "Emerald Green", "Large" or "Emerald Green / Large". */
        public String label() {
            if (color != null && size != null) {
                return color + " / " + size;
            }
            return color != null ? color : size;
        }
    }

    private VariantCombos() {}

    public static List<Combo> build(List<String> colors, List<String> sizes) {
        List<String> cs = clean(colors);
        List<String> ss = clean(sizes);
        List<Combo> out = new ArrayList<>();
        if (cs.isEmpty() && ss.isEmpty()) {
            return out;
        }
        if (ss.isEmpty()) {
            cs.forEach(c -> out.add(new Combo(c, null)));
        } else if (cs.isEmpty()) {
            ss.forEach(s -> out.add(new Combo(null, s)));
        } else {
            for (String c : cs) {
                for (String s : ss) {
                    out.add(new Combo(c, s));
                }
            }
        }
        return out;
    }

    /** Trimmed, blanks dropped, repeats (any case) dropped, first spelling kept. */
    public static List<String> clean(List<String> raw) {
        Map<String, String> seen = new LinkedHashMap<>();
        if (raw != null) {
            for (String r : raw) {
                if (r == null) {
                    continue;
                }
                String t = r.replaceAll("\\s+", " ").trim();
                if (!t.isEmpty()) {
                    seen.putIfAbsent(t.toLowerCase(Locale.ROOT), t.length() > 60 ? t.substring(0, 60).trim() : t);
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** The variant's own name: the full product title, a dash, then the option. Cut so it fits the 250-character column. */
    public static String variantName(String productName, String optionLabel) {
        String suffix = " – " + optionLabel;
        String title = productName == null ? "" : productName.trim();
        int room = 250 - suffix.length();
        if (title.length() > room) {
            title = title.substring(0, Math.max(0, room)).trim();
        }
        return title + suffix;
    }

    /**
     * A colour name tidied the way the wizard tidies it (variantWizard.ts tidyColourWords; keep the two the same): a first
     * capital on every plain lower-case word, codes left as they are, and a phrase typed twice in a row kept once
     * ("candy Colors 17 candy Colors 17" is "Candy Colors 17").
     */
    public static String tidyColour(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) {
            return t;
        }
        String[] words = t.split(" ");
        int n = words.length;
        if (n % 2 == 0) {
            int half = n / 2;
            boolean doubled = true;
            for (int i = 0; i < half; i++) {
                if (!words[i].equalsIgnoreCase(words[half + i])) {
                    doubled = false;
                    break;
                }
            }
            if (doubled) {
                n = half;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String w = words[i];
            if (w.matches("[a-z][a-z'’-]*")) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            if (out.length() > 0) out.append(' ');
            out.append(w);
        }
        return out.toString();
    }

    /** "#RRGGBB" in upper case when {@code hex} is one (with or without the #); otherwise null. */
    public static String normaliseHex(String hex) {
        if (hex == null) {
            return null;
        }
        String h = hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        return h.matches("(?i)[0-9a-f]{6}") ? "#" + h.toUpperCase(Locale.ROOT) : null;
    }
}
