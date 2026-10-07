package com.nbh.backend.imagecleanup;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The views staff can tick when they ask the AI for photos of an option (owner, 2026-10-07: staff must be able to
 * specify the angles). The first one stays closest to the real photo; the others extrapolate more. Each states its
 * own frame-fill target, because descriptive wording alone returned near-identical crops when this was tried.
 */
public enum PhotoView {

    CLEAN("Clean photo", "Same angle as your photo, tidied up on a studio backdrop",
            "Hero shot: keep exactly the same angle and pose as the reference photo — do not invent a new angle, "
                    + "just clean it up. The product occupies no more than 60% of the frame's width and height, "
                    + "generous margin on every side."),
    DETAIL("Close-up", "Same angle, zoomed right in on the texture and label",
            "Detail shot: the exact same camera angle and pose as the reference — do not rotate or change the "
                    + "viewpoint — but crop in MUCH closer so the label, texture and surface material read clearly. "
                    + "Every visible detail must match the reference where the crop overlaps. The product fills at "
                    + "least 85% of the frame, dramatically closer than a normal product shot."),
    WIDE("Wide view", "Three-quarter view from the other side, with more space around it",
            "Wide shot: a three-quarter view from the opposite side to the reference photo, framed WIDER so the whole "
                    + "silhouette and surrounding space show. Extrapolate this angle, keeping the real colour, shape "
                    + "and any visible label exactly consistent with the photo. The product occupies no more than "
                    + "50% of the frame, with clearly more empty space than the hero shot."),
    FRONT("Front view", "Straight on, centred",
            "Front view: straight-on and centred, camera at the product's mid-height, medium framing (product about "
                    + "65% of the frame). Extrapolate this view from the reference, keeping colour, shape and any "
                    + "visible label consistent."),
    ELEVATED("From above (45°)", "Camera raised, looking down at an angle",
            "Elevated view: camera raised about 45 degrees looking down at the product, medium framing (product "
                    + "about 60% of the frame). Extrapolate this view, keeping colour, shape and any visible label "
                    + "consistent with the reference."),
    SIDE("Side view", "A clean side profile",
            "Side view: a clean side profile, product about 60% of the frame, so the depth and shape read clearly. "
                    + "Extrapolate this view, keeping colour, shape and any visible label consistent with the "
                    + "reference."),
    TOP_DOWN("Top-down", "Straight down from above, like a flat lay",
            "Top-down view: the camera directly above the product looking straight down, as in a flat-lay photo, "
                    + "product about 60% of the frame, so its top and outline read clearly. Extrapolate this view, "
                    + "keeping colour, shape and any visible label consistent with the reference."),
    BACK("Back view", "The product seen from behind",
            "Back view: the product seen from behind, medium framing (product about 60% of the frame). Extrapolate "
                    + "this view from the reference, keeping colour, shape and any visible label consistent — do not "
                    + "invent text, barcodes or details on the back that are not visible in the photo.");

    private final String label;
    private final String hint;
    private final String instruction;

    PhotoView(String label, String hint, String instruction) {
        this.label = label;
        this.hint = hint;
        this.instruction = instruction;
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    /** What the model is told about this view's angle and framing. */
    public String instruction() {
        return instruction;
    }

    /** The views used when nobody ticked any: the first {@code count} in this order (the original six, then the new two). */
    public static List<PhotoView> firstOf(int count) {
        int n = Math.max(1, Math.min(count, values().length));
        return Arrays.asList(values()).subList(0, n);
    }

    public static Optional<PhotoView> parse(String key) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(key.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
