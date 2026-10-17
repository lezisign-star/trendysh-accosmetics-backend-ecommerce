package com.nbh.backend.variantgen;

import com.nbh.backend.imagecleanup.PhotoView;
import org.springframework.stereotype.Component;

/**
 * The edit instruction for one generated variant's photo. Unlike the photo studio's prompt, the product's COLOUR and
 * the SIZE written on its label are allowed to change (that is the whole point); everything else about the product is
 * ground truth from the base photo. The model is told to look for label text that differs between the variants (the
 * colour name, the size) and update only that, following the staff's words about labels when they gave any.
 */
@Component
public class VariantPhotoPromptBuilder {

    public static final int MAX_LABEL_NOTE = 600;

    public String build(String productName, String color, String swatchHex, String size, String labelNote,
                        String backgroundHex) {
        return build(productName, color, swatchHex, size, labelNote, backgroundHex, null, null, false);
    }

    /**
     * @param whereNote     staff's words for every colour: where the shade shows and how the product looks
     * @param groupNote     staff's words for this colour's group (a series that looks different, e.g. black bottles)
     * @param withReference a second photo is attached: a real product of this range, to look like
     */
    public String build(String productName, String color, String swatchHex, String size, String labelNote,
                        String backgroundHex, String whereNote, String groupNote, boolean withReference) {
        return build(productName, color, swatchHex, size, labelNote, backgroundHex, whereNote, groupNote, withReference,
                false);
    }

    /**
     * @param withMask staff marked the part that may change, and the mask is attached as the LAST picture. The photo is
     *                 then kept exactly as it is (its own background, framing and size): asking for a studio backdrop
     *                 made the AI repaint and re-centre it, and pasting the product's photo back around the mark left a
     *                 pale patch where the cap should be (Roniki, 2026-10-17).
     */
    public String build(String productName, String color, String swatchHex, String size, String labelNote,
                        String backgroundHex, String whereNote, String groupNote, boolean withReference,
                        boolean withMask) {
        StringBuilder p = new StringBuilder();
        p.append("The attached image is a real, unedited photo of a product a shop sells: \"").append(productName)
                .append("\". Make the photo of ANOTHER VARIANT of this exact product.\n\n");
        p.append("THE ONLY THINGS THAT CHANGE FOR THIS VARIANT:\n");
        if (color != null) {
            // Owner, 2026-10-15: "every part that shares its main colour" painted white gel polish bottles all over,
            // when the real ones show the shade only on the top of the cap. Only the part that shows the shade changes.
            p.append("- Colour: this variant's shade is \"").append(color).append("\"")
                    .append(swatchHex != null ? " (about " + swatchHex + ")" : "")
                    .append(". First find the part of the product in the photo that shows its shade. Often only part ")
                    .append("of a product does (for example the top of a cap, a small window, a swatch, or the liquid ")
                    .append("seen through clear glass) while the bottle, cap, box or label is white, black, clear, ")
                    .append("silver or printed. Change ONLY the part that shows the shade to the new shade; every ")
                    .append("other part keeps exactly its own colour (a white bottle stays white, a black one stays ")
                    .append("black). Only when the whole product is plainly one colour, recolour all of it. Keep the ")
                    .append("real material, finish, shimmer or glitter, reflections and shading, so it looks like the ")
                    .append("same product in that shade.\n");
            String where = clean(whereNote);
            if (where != null) {
                p.append("- The shop's own words on where the shade shows and how the product looks (follow them ")
                        .append("exactly, over your own judgement): \"").append(where).append("\"\n");
            }
            String group = clean(groupNote);
            if (group != null) {
                p.append("- This variant is one of a series that looks different from the photo. The shop says: \"")
                        .append(group).append("\". Make the product look like that (for example the colour of ")
                        .append("its bottle or cap), keeping its shape, label, logo and framing. Where these words ")
                        .append("and the shop's words for every colour disagree, these win for this variant.\n");
            }
            if (withReference) {
                p.append("- A SECOND photo is attached after the first: a real, unedited photo of a product from ")
                        .append("this same range as it really looks. Match it for which parts are white, black or ")
                        .append("clear and where the shade shows, but use THIS variant's shade, and edit the FIRST ")
                        .append("photo (its framing and angle), not the second.\n");
            }
        }
        if (size != null) {
            p.append("- Size: this variant is the \"").append(size).append("\" size. Where the product or its packaging ")
                    .append("shows its size or capacity in writing, change that text to \"").append(size).append("\". ")
                    .append("Do not redraw the product at a different scale or invent a size where none is printed.\n");
        }
        p.append("- Label text: look carefully at any label, sticker, box or printed text on the product. If it names ")
                .append("the colour or size, update only that wording to match this variant. Leave every other word, ")
                .append("logo and graphic exactly as in the photo.\n");
        String note = labelNote == null ? null : labelNote.replaceAll("\\s+", " ").trim();
        if (note != null && !note.isEmpty()) {
            p.append("- The shop's own words about labels and details for this variant (follow them for label text ")
                    .append("only): \"").append(note.length() > MAX_LABEL_NOTE ? note.substring(0, MAX_LABEL_NOTE) : note)
                    .append("\"\n");
        }
        p.append("\nEVERYTHING ELSE stays exactly as in the photo: shape, proportions, parts, count of items, angle and ")
                .append("framing. STRICT RULES: do not add a lid, cap, handle, pump or any part not in the photo; do not ")
                .append("add texture, labels or text beyond the allowed label update; show exactly the items in the ")
                .append("photo, no extras or props; do not add any watermark, logo or caption of your own.\n\n");
        if (withMask) {
            p.append("The LAST attached picture is a mask, not a photo: it is white over the only area you may change ")
                    .append("and black everywhere else. Change pixels only inside the white area.\n\n");
            p.append("KEEP THE PHOTO EXACTLY AS IT IS: the same background, lighting, framing, crop, picture size and ")
                    .append("shape. Do not move, resize, re-centre or rotate the product, do not replace or clean the ")
                    .append("background, and do not add a studio backdrop or shadow. Every pixel outside the white area ")
                    .append("must stay where it is.\n\n");
        } else {
            p.append("Make it a professional studio catalogue photo. ").append(PhotoView.CLEAN.instruction())
                    .append(" Use a seamless solid creamy studio backdrop, hex ").append(backgroundHex)
                    .append(", no gradient or texture, soft even studio lighting, a soft realistic ground shadow and a ")
                    .append("subtle reflection. Keep the product centred with a clear margin.\n\n");
        }
        p.append("Before finalising, compare with the ").append(withReference ? "first " : "")
                .append("attached photo: only the colour of the part that shows the shade")
                .append(size != null ? ", the size wording" : "")
                .append(" and label wording named above may differ.\n\n");
        p.append(withMask ? "Return the photo at the same size and shape as the first attached photo."
                : "Square 1:1 composition, 1024x1024 pixels.");
        return p.toString();
    }

    private static String clean(String text) {
        if (text == null) return null;
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > MAX_LABEL_NOTE ? t.substring(0, MAX_LABEL_NOTE) : t;
    }

    /**
     * The variant jobs' second photo step (VARIANTS_PLAN.md 3.2): the colour is already right in the attached photo; only
     * the size printed on the product or its packaging changes, to the text staff gave for that size.
     */
    public String labelOnly(String productName, String labelText, String backgroundHex) {
        String text = labelText.replaceAll("\\s+", " ").trim();
        return "The attached image is a catalogue photo of a product a shop sells: \"" + productName + "\". Make the photo "
                + "of the same product in another size.\n\n"
                + "THE ONLY THING THAT CHANGES: where the product or its packaging shows its size or capacity in writing "
                + "(for example \"50ml\", \"3.5g\", \"Large\"), change that text to exactly \"" + text + "\", in the same "
                + "font, colour, size and position as the text it replaces. If no size is written anywhere, change nothing.\n\n"
                + "EVERYTHING ELSE stays exactly as in the photo: the colour, shape, proportions, parts, every other word, "
                + "logo and graphic, the angle, framing, the " + backgroundHex + " backdrop, lighting and shadow. Do not add "
                + "any text, watermark, logo or caption of your own.\n\n"
                + "Square 1:1 composition, 1024x1024 pixels.";
    }
}
