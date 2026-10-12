package com.nbh.backend.variantjobs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * How each new variant gets its photo with as few AI calls as possible (VARIANTS_PLAN.md 3.2):
 * <ol>
 *   <li>The starting photo is the size's own photo when staff gave one (so a bigger bottle looks bigger), else the
 *       product's photo.</li>
 *   <li>A colour makes a <em>colour master</em>: that starting photo recoloured, once per colour and starting photo.
 *       With no colour the starting photo itself is the master.</li>
 *   <li>A size with label text gets the master with only the printed size changed: one call per variant. Without label
 *       text the variant gets the master as it is (its own photo row; the file is shared, no call).</li>
 * </ol>
 * The estimate and the worker both use this, so the estimate is what the job really does.
 */
public final class PhotoPlan {

    public record Want(String color, String size) {}

    /** {@code sourcePhotoId} null = the product's photo. */
    public record Master(String color, UUID sourcePhotoId) {
        public boolean needsAi() {
            return color != null;
        }
    }

    public record Step(Want want, Master master, String labelText) {
        public boolean labelEdit() {
            return labelText != null;
        }
    }

    private final List<Step> steps;

    private PhotoPlan(List<Step> steps) {
        this.steps = steps;
    }

    public static PhotoPlan of(List<Want> wants, Function<String, UUID> sizePhoto, Function<String, String> sizeLabel) {
        List<Step> steps = new ArrayList<>();
        for (Want w : wants) {
            UUID source = w.size() == null ? null : sizePhoto.apply(w.size());
            String label = w.size() == null ? null : sizeLabel.apply(w.size());
            steps.add(new Step(w, new Master(w.color(), source), label));
        }
        return new PhotoPlan(steps);
    }

    public List<Step> steps() {
        return steps;
    }

    /** Recolours (one per colour master) plus label edits (one per variant whose size has label text). */
    public int aiCalls() {
        Set<Master> masters = new LinkedHashSet<>();
        int labels = 0;
        for (Step s : steps) {
            if (s.master().needsAi()) masters.add(s.master());
            if (s.labelEdit()) labels++;
        }
        return masters.size() + labels;
    }

    public Step stepFor(String color, String size) {
        return steps.stream().filter(s -> Objects.equals(s.want().color(), color) && Objects.equals(s.want().size(), size))
                .findFirst().orElse(null);
    }
}
