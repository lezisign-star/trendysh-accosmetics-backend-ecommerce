package com.nbh.backend.variantjobs;

import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.variantjobs.VariantJobRequest.PhotoGroup;
import com.nbh.backend.variantjobs.VariantJobRequest.PhotoGuide;
import com.nbh.backend.variantjobs.VariantJobRequest.TrialPhoto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What staff said about how a job's photos must look (owner, 2026-10-15: a gel polish whose colour shows only on the
 * top of a white bottle came back painted all over, and a series with black bottles came back white). For one colour:
 * the words for all colours, the words of the group it is in, the real photo to follow (the group's, else the one for
 * all colours) and the photo staff already tried and approved for it.
 */
public record PhotoGuides(PhotoGuide guide, List<PhotoGroup> groups, List<TrialPhoto> trials) {

    public static final int MAX_NOTE = 600;
    public static final int MAX_GROUPS = 20;

    public static PhotoGuides none() {
        return new PhotoGuides(null, null, null);
    }

    /** Staff's words for every colour, cleaned; null when none. */
    public String generalNote() {
        return guide == null ? null : clean(guide.note());
    }

    /** The group this colour is in (the first that names it, any case), or null. */
    public PhotoGroup groupOf(String color) {
        if (groups == null || color == null) {
            return null;
        }
        String key = key(color);
        for (PhotoGroup g : groups) {
            if (g != null && g.colors() != null && g.colors().stream().anyMatch(c -> c != null && key(c).equals(key))) {
                return g;
            }
        }
        return null;
    }

    public String groupNote(String color) {
        PhotoGroup g = groupOf(color);
        return g == null ? null : clean(g.note());
    }

    /** The real photo to follow for this colour: its group's, else the one for all colours; null when none. */
    public String referenceFor(String color) {
        PhotoGroup g = groupOf(color);
        if (g != null && blankToNull(g.referenceUrl()) != null) {
            return g.referenceUrl().trim();
        }
        return guide == null ? null : blankToNull(guide.referenceUrl());
    }

    /** The picture marking the part of the photo that may change for every colour, or null when staff marked none. */
    public String maskUrl() {
        return guide == null ? null : blankToNull(guide.maskUrl());
    }

    /** A marked part and the photo it was marked on (null when not known). */
    public record Mask(String url, String photoUrl) {
        /** Whether it may be used on this starting photo: the one it was marked on (or any, when not known). */
        public boolean fits(String sourceUrl) {
            return photoUrl == null || sourceUrl == null || photoUrl.equals(sourceUrl.trim());
        }
    }

    /** The marked part for this colour: its group's own, else the general one; null when none. */
    public Mask maskFor(String color) {
        PhotoGroup g = groupOf(color);
        if (g != null && blankToNull(g.maskUrl()) != null) {
            return new Mask(g.maskUrl().trim(), blankToNull(g.maskPhotoUrl()));
        }
        return maskUrl() == null ? null : new Mask(maskUrl(), blankToNull(guide.maskPhotoUrl()));
    }

    /** Made without AI: the marked part of the product's own photo is recoloured in code ({@link CodeRecolour}). */
    public boolean byCode() {
        return guide != null && guide.method() != null && guide.method().trim().equalsIgnoreCase("CODE");
    }

    /** The general marked part (the one for every colour, e.g. the cap), or null. */
    public Mask generalMask() {
        return maskUrl() == null ? null : new Mask(maskUrl(), blankToNull(guide.maskPhotoUrl()));
    }

    /**
     * Made without AI: this colour's group's own marked part and the colour it is painted (a black-bottle series), or
     * null when the colour's group has neither.
     */
    public Paint groupPaint(String color) {
        PhotoGroup g = groupOf(color);
        if (g == null || blankToNull(g.maskUrl()) == null || blankToNull(g.paintHex()) == null) return null;
        return new Paint(new Mask(g.maskUrl().trim(), blankToNull(g.maskPhotoUrl())), g.paintHex().trim());
    }

    public record Paint(Mask mask, String hex) {}

    /** The photo staff tried and approved for this colour, or null. */
    public String trialFor(String color) {
        if (trials == null || color == null) {
            return null;
        }
        String key = key(color);
        return trials.stream().filter(t -> t != null && t.color() != null && key(t.color()).equals(key))
                .map(t -> blankToNull(t.url())).filter(u -> u != null).findFirst().orElse(null);
    }

    /**
     * Refuses what the photo steps can't use: a photo that is not one of the shop's own stored files (only those are
     * downloaded), too many groups, a colour in two groups. The words themselves are cut to {@link #MAX_NOTE}.
     */
    public void check(Predicate<String> ownUrl) {
        List<String> urls = new ArrayList<>();
        if (guide != null && blankToNull(guide.referenceUrl()) != null) urls.add(guide.referenceUrl().trim());
        if (maskUrl() != null) urls.add(maskUrl());
        if (groups != null) {
            if (groups.size() > MAX_GROUPS) {
                throw new UnprocessableException("Keep it to " + MAX_GROUPS + " groups of colours at most.");
            }
            Set<String> seen = new HashSet<>();
            for (PhotoGroup g : groups) {
                if (g == null) continue;
                if (blankToNull(g.referenceUrl()) != null) urls.add(g.referenceUrl().trim());
                if (blankToNull(g.maskUrl()) != null) urls.add(g.maskUrl().trim());
                for (String c : g.colors() == null ? List.<String>of() : g.colors()) {
                    if (c != null && !seen.add(key(c))) {
                        throw new UnprocessableException("\"" + c.trim() + "\" is in two groups. Keep each colour in one.");
                    }
                }
            }
        }
        if (trials != null) {
            trials.stream().filter(t -> t != null && blankToNull(t.url()) != null).forEach(t -> urls.add(t.url().trim()));
        }
        for (String u : urls) {
            if (!ownUrl.test(u)) {
                throw new UnprocessableException("A photo in the instructions isn't one of the shop's own. Add it again.");
            }
        }
    }

    static String clean(String note) {
        if (note == null) return null;
        String n = note.replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) return null;
        return n.length() > MAX_NOTE ? n.substring(0, MAX_NOTE).trim() : n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String key(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
