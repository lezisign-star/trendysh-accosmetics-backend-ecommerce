package com.nbh.backend.catalog.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The staff product list's search box (2026-10-17, owner: "make the searching even more advanced so its coverage
 * addresses the listings as much as possible").
 *
 * <ul>
 *   <li>Every word typed must be found somewhere in the product, in any order: "roniki pink" finds the Roniki gel
 *       whose Hot Pink option is PU30.</li>
 *   <li>Dashes and other marks are ignored on both sides, so "PU30", "pu 30" and "PU-30" are the same, and
 *       "hotpink" finds "Hot Pink" (spaces are ignored only for words with a digit or of five letters or more, see
 *       {@link #joinable}).</li>
 *   <li>Looked in: the product name, brand, categories, each live option's code (SKU), name, colour, label, size and
 *       colour family, its barcodes, and last of all the short description and highlights.</li>
 *   <li>Best first: an exact code or barcode, then names starting with what was typed, names containing it, names
 *       holding every word, then matches on an option, category or brand, then description-only matches; newest first
 *       within each.</li>
 * </ul>
 *
 * Returns the matching product ids in that order; {@link ProductService#listProducts} applies the list's filters and
 * pages them. A catalogue of a few thousand products is scanned in one statement; {@link #MAX_RESULTS} bounds it.
 */
@Service
public class ProductSearch {

    /** More words than this are ignored (a pasted paragraph is not a search). */
    static final int MAX_WORDS = 8;
    /** The most matches kept; a search wide enough to match more is narrowed by typing more. */
    static final int MAX_RESULTS = 2000;

    @PersistenceContext
    private EntityManager em;

    /** Lower case, letters and digits only: how both the words and the product's text are compared. */
    static String compact(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** The words of a search, each compacted; none when nothing searchable was typed (only marks, or blank). */
    static List<String> words(String q) {
        if (q == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : q.trim().split("\\s+")) {
            String w = compact(part);
            if (!w.isEmpty()) out.add(w);
            if (out.size() == MAX_WORDS) break;
        }
        return new ArrayList<>(out);
    }

    /**
     * Whether a word may also be found with the product's spaces taken out ("pu30" in "PU 30", "hotpink" in "Hot
     * Pink"). Only for words with a digit or of five letters or more: a short word would otherwise be found across
     * a space ("red" in "Bare Draft"), which found the live check on 2026-10-17.
     */
    static boolean joinable(String word) {
        return word.length() >= 5 || word.chars().anyMatch(Character::isDigit);
    }

    /** Word {@code i} in a field: within its words, and also with its spaces taken out when {@link #joinable}. */
    private static String in(String field, int i, boolean joined) {
        String spaced = field + "_s LIKE :w" + i;
        return joined ? "(" + spaced + " OR " + field + "_c LIKE :w" + i + ")" : spaced;
    }

    /**
     * The ids of the products matching {@code q}, best first. Empty when nothing matches; the caller treats a
     * {@code q} with no searchable words as no search at all (see {@link #words}).
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<UUID> rankedIds(String q, boolean deleted) {
        List<String> words = words(q);
        if (words.isEmpty()) return List.of();
        String whole = String.join("", words);
        String spaced = String.join(" ", words);

        // Only parameter names are put into the statement; every value typed is bound.
        StringBuilder inName = new StringBuilder();
        StringBuilder inCore = new StringBuilder();
        StringBuilder inAll = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String and = i == 0 ? "" : " AND ";
            boolean joined = joinable(words.get(i));
            inName.append(and).append(in("name", i, joined));
            inCore.append(and).append(in("core", i, joined));
            inAll.append(and).append("(").append(in("core", i, joined)).append(" OR ")
                    .append(in("more", i, joined)).append(")");
        }
        boolean wholeJoined = joinable(whole);
        String nameStarts = "name_s LIKE :spacedStart" + (wholeJoined ? " OR name_c LIKE :wholeStart" : "");
        String nameHas = "name_s LIKE :spacedAny" + (wholeJoined ? " OR name_c LIKE :wholeAny" : "");
        // _s: marks become spaces ("PU-30" is "pu 30"); _c: letters and digits only ("pu30").
        String s = "'[^[:alnum:]]+', ' ', 'g'";
        String c = "'[^[:alnum:]]+', '', 'g'";
        String sql = """
                WITH raw AS (
                  SELECT p.id, p.created_at,
                    lower(p.name) AS name_t,
                    lower(concat_ws(' ', p.name, b.name,
                      (SELECT string_agg(concat_ws(' ', v.sku, v.variant_name, v.option_color, v.option_label,
                                                   v.option_size, v.color_family), ' ')
                         FROM product_variant v WHERE v.product_id = p.id AND v.deleted = false),
                      (SELECT string_agg(vb.barcode, ' ')
                         FROM variant_barcode vb JOIN product_variant v ON v.id = vb.variant_id
                        WHERE v.product_id = p.id AND v.deleted = false),
                      (SELECT string_agg(cat.name, ' ')
                         FROM product_category pc JOIN category cat ON cat.id = pc.category_id
                        WHERE pc.product_id = p.id))) AS core_t,
                    lower(concat_ws(' ', p.short_description, p.highlights)) AS more_t,
                    (EXISTS (SELECT 1 FROM product_variant v
                              WHERE v.product_id = p.id AND v.deleted = false
                                AND regexp_replace(lower(coalesce(v.sku, '')), %2$s) = :whole)
                     OR EXISTS (SELECT 1 FROM variant_barcode vb JOIN product_variant v ON v.id = vb.variant_id
                                 WHERE v.product_id = p.id AND v.deleted = false
                                   AND regexp_replace(lower(vb.barcode), %2$s) = :whole)) AS exact_code
                  FROM product p LEFT JOIN brand b ON b.id = p.brand_id
                  WHERE p.deleted = :deleted
                ), hay AS (
                  SELECT id, created_at, exact_code,
                    ' ' || regexp_replace(name_t, %1$s) || ' ' AS name_s, regexp_replace(name_t, %2$s) AS name_c,
                    ' ' || regexp_replace(core_t, %1$s) || ' ' AS core_s, regexp_replace(core_t, %2$s) AS core_c,
                    ' ' || regexp_replace(more_t, %1$s) || ' ' AS more_s, regexp_replace(more_t, %2$s) AS more_c
                  FROM raw
                )
                SELECT CAST(id AS varchar) FROM hay
                WHERE %5$s
                ORDER BY CASE WHEN exact_code THEN 0
                              WHEN %6$s THEN 1
                              WHEN %7$s THEN 2
                              WHEN %3$s THEN 3
                              WHEN %4$s THEN 4
                              ELSE 5 END,
                         created_at DESC, id DESC
                LIMIT %8$d
                """.formatted(s, c, inName, inCore, inAll, nameStarts, nameHas, MAX_RESULTS);

        var query = em.createNativeQuery(sql)
                .setParameter("deleted", deleted)
                .setParameter("whole", whole)
                .setParameter("spacedStart", " " + spaced + "%")
                .setParameter("spacedAny", "%" + spaced + "%");
        if (wholeJoined) {
            query.setParameter("wholeStart", whole + "%").setParameter("wholeAny", "%" + whole + "%");
        }
        for (int i = 0; i < words.size(); i++) query.setParameter("w" + i, "%" + words.get(i) + "%");
        return ((List<Object>) query.getResultList()).stream().map(o -> UUID.fromString(o.toString())).toList();
    }
}
