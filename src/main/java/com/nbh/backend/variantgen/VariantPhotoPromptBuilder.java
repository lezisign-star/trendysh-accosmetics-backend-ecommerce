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
        StringBuilder p = new StringBuilder();
        p.append("The attached image is a real, unedited photo of a product a shop sells: \"").append(productName)
                .append("\". Make the photo of ANOTHER VARIANT of this exact product.\n\n");
        p.append("THE ONLY THINGS THAT CHANGE FOR THIS VARIANT:\n");
        if (color != null) {
            p.append("- Colour: recolour the product (every part that shares its main colour: body, lid, handles, ")
                    .append("trim that is the same colour) to \"").append(color).append("\"")
                    .append(swatchHex != null ? " (about " + swatchHex + ")" : "")
                    .append(". Keep the real material, finish, speckle or pattern, reflections and shading, so it looks ")
                    .append("like the same product made in that colour. Do not recolour parts that are clearly a ")
                    .append("different material (glass, metal, clear plastic).\n");
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
        p.append("Make it a professional studio catalogue photo. ").append(PhotoView.CLEAN.instruction())
                .append(" Use a seamless solid creamy studio backdrop, hex ").append(backgroundHex)
                .append(", no gradient or texture, soft even studio lighting, a soft realistic ground shadow and a ")
                .append("subtle reflection. Keep the product centred with a clear margin.\n\n");
        p.append("Before finalising, compare with the attached photo: only the colour")
                .append(size != null ? ", the size wording" : "")
                .append(" and label wording named above may differ.\n\n");
        p.append("Square 1:1 composition, 1024x1024 pixels.");
        return p.toString();
    }
}
