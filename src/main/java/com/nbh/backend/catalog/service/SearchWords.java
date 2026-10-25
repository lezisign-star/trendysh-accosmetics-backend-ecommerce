package com.nbh.backend.catalog.service;

import java.util.*;

/**
 * What else a searched word may be found as (owner, 2026-10-17: "searching a term returns all products containing the
 * term in their name, description, category, related by any chance"). Each typed word stands for itself, its
 * singular ("lashes" → "lash"), the words that mean the same in a beauty shop ("eyelash", "colour"/"color"), and, when
 * the word is found nowhere in the catalogue, the catalogue's words one or two letters away from it (a typo:
 * "nial" → "nail", "gell" → "gel"). Words are compared compacted: lower case, letters and digits only.
 */
public final class SearchWords {

    private SearchWords() {}

    /** Words that mean the same thing to a shopper; each group is searched as one. Compacted, singular. */
    static final List<Set<String>> SAME = List.of(
            Set.of("lash", "eyelash", "lashe"),
            Set.of("brow", "eyebrow"),
            Set.of("nail", "manicure", "pedicure"),
            Set.of("polish", "varnish", "lacquer"),
            Set.of("lip", "lipstick", "lipgloss"),
            Set.of("color", "colour"),
            Set.of("grey", "gray"),
            Set.of("makeup", "cosmetic"),
            Set.of("glue", "adhesive"),
            Set.of("tweezer", "tweezers"),
            Set.of("wax", "waxing"),
            Set.of("blush", "blusher"),
            Set.of("liner", "eyeliner"),
            Set.of("lamp", "light", "dryer"),
            Set.of("remover", "acetone"),
            Set.of("sponge", "blender"),
            Set.of("kit", "package", "set"));

    /** "lashes" → "lash", "brushes" → "brush", "nails" → "nail"; short words and "-ss" words are left as they are. */
    static String singular(String w) {
        if (w.length() > 4 && (w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes") || w.endsWith("sses"))) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.matches(".*\\d.*")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /**
     * The alternatives for one typed word, the word itself first. {@code vocabulary}: the catalogue's own words, for
     * typos; null or empty = no typo help.
     */
    public static List<String> alternatives(String word, Collection<String> vocabulary) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(word);
        String one = singular(word);
        out.add(one);
        for (Set<String> group : SAME) {
            if (group.contains(word) || group.contains(one)) out.addAll(group);
        }
        if (vocabulary != null && !vocabulary.isEmpty() && word.length() >= 4
                && word.chars().noneMatch(Character::isDigit)
                && out.stream().noneMatch(alt -> vocabulary.stream().anyMatch(v -> v.contains(alt)))) {
            int max = word.length() <= 5 ? 1 : 2;
            vocabulary.stream()
                    .filter(v -> Math.abs(v.length() - word.length()) <= max && v.length() >= 3)
                    .filter(v -> distance(word, v, max) <= max)
                    .sorted(Comparator.comparingInt((String v) -> distance(word, v, max)).thenComparing(v -> v))
                    .limit(4)
                    .forEach(out::add);
        }
        out.removeIf(String::isEmpty);
        return new ArrayList<>(out);
    }

    /** Edit distance, stopping early once it is past {@code max}. */
    static int distance(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) return max + 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) return max + 1;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
