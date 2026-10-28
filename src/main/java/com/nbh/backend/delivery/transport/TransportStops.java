package com.nbh.backend.delivery.transport;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How a typed drop-off location is tidied before it is kept and shown to other shoppers (owner, 2026-10-28: shown
 * straight away, in Title Case for a better display), and how two spellings of the same place are matched.
 */
public final class TransportStops {

    private TransportStops() {}

    /** Words left lower case inside a name ("Opposite the Shell"), unless they start it. */
    private static final Set<String> SMALL = Set.of("and", "of", "the", "at", "by", "on", "to", "in", "opp", "near");
    /** Kept in capitals: they are names, not words. */
    private static final Set<String> CAPS = Set.of("cbd", "gpo", "knh", "kicc", "jkia", "ku", "tsc", "kbs", "ii", "iii");
    /** Words that do not tell two places apart ("Kencom Stage" is "Kencom"). */
    private static final Set<String> FILLER = Set.of("stage", "stop", "the", "bus", "station", "terminus");

    /**
     * The name as kept and shown: anything that looks like a phone number taken out (a shopper's own number must never
     * be shown to the next one), spaces and stray punctuation tidied, and Title Case. Null when nothing is left.
     */
    public static String clean(String typed) {
        if (typed == null) return null;
        String text = typed
                .replaceAll("\\+?\\d[\\d\\s-]{6,}\\d", " ")   // phone numbers and other long digit runs
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[^\\p{L}\\p{N} ,.'&()/-]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([,.])", "$1")
                .trim()
                .replaceAll("^[,.\\-/ ]+|[,\\-/ ]+$", "");
        if (text.isBlank()) return null;
        String[] words = text.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append(' ');
            out.append(titleWord(words[i], i == 0));
        }
        String result = out.toString();
        return result.length() <= 120 ? result : result.substring(0, 120).trim();
    }

    private static String titleWord(String word, boolean first) {
        String bare = word.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
        if (CAPS.contains(bare)) return word.toUpperCase(Locale.ROOT);
        if (!first && SMALL.contains(bare)) return word.toLowerCase(Locale.ROOT);
        // "kwa-mama" -> "Kwa-Mama"; "o'neill" -> "O'Neill"; a word with digits ("phase2") stays as typed but lower.
        StringBuilder b = new StringBuilder(word.toLowerCase(Locale.ROOT));
        boolean start = true;
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (Character.isLetter(c)) {
                if (start) b.setCharAt(i, Character.toUpperCase(c));
                start = false;
            } else {
                start = c == '-' || c == '\'' || c == '(' || c == '/';
            }
        }
        return b.toString();
    }

    /** The name reduced for matching: lower case, letters and digits only, filler words out. */
    public static String key(String name) {
        if (name == null) return "";
        String reduced = Arrays.stream(name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").split("\\s+"))
                .filter(w -> !w.isBlank() && !FILLER.contains(w))
                .collect(Collectors.joining(" "));
        String k = reduced.isBlank() ? name.toLowerCase(Locale.ROOT).trim() : reduced;
        return k.length() <= 120 ? k : k.substring(0, 120);
    }
}
