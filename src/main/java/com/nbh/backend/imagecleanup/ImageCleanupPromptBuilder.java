package com.nbh.backend.imagecleanup;

import org.springframework.stereotype.Component;

/**
 * Builds the Gemini edit instruction for one view of an option. The real photo is treated as ground truth: the
 * allowed edits are listed first, then an itemised don't-add list (the failure modes seen when this approach
 * was tried on another catalogue: invented lids, textures, labels, extra units). The view states its own
 * frame-fill target — descriptive wording alone returned near-identical crops. The logo is NOT drawn by the
 * model (image models garble small precise marks); {@link WatermarkService} composites it afterwards.
 *
 * <p>Staff can add a note in their own words (owner, 2026-10-07: "specify the angles and specifications they need").
 * It may change the SCENE — a different surface or backdrop, lighting, framing — but never the product itself: the
 * strict rules come before it and are stated to win over it.
 */
@Component
public class ImageCleanupPromptBuilder {

    /** Longest note staff may add; keeps the instruction focused and bounded. */
    public static final int MAX_NOTE_LENGTH = 500;

    public String build(String productName, PhotoView view, String note, String backgroundHex) {
        StringBuilder p = new StringBuilder();
        p.append("The attached image is a real, unedited photo of an actual product a beauty-supplies shop sells: \"")
                .append(productName).append("\". Treat it as the exact, complete ground truth for what this ")
                .append("product is — its colour, shape, material, surface finish and any visible label or ")
                .append("branding. Do not change what the product actually looks like.\n\n");
        p.append("The ONLY changes you may make are: replacing the background, correcting lighting, exposure and ")
                .append("colour balance, removing clutter, hands, other objects and messy surfaces, adding a soft ")
                .append("shadow and reflection for depth, and — only where the shot below asks for a different ")
                .append("angle — extrapolating that camera angle. Every other detail of the product itself must be ")
                .append("carried over unchanged.\n\n");
        p.append("STRICT RULES:\n")
                .append("- Do not add a lid, cap, cover, pump or any part not visibly attached in the photo.\n")
                .append("- Do not add surface texture, ribbing, embossing or any pattern not visible in the photo.\n")
                .append("- Do not add labels, stickers, printed text or branding beyond exactly what the photo shows.\n")
                .append("- Do not change proportions, colour or material.\n")
                .append("- Show exactly one item, matching the count and arrangement in the photo where the angle ")
                .append("allows. No duplicates, no extra items, no props.\n")
                .append("- Do not add any watermark, logo, caption or text of your own.\n\n");
        p.append("Make it a professional studio catalogue photo. ").append(view.instruction())
                .append(" Use a seamless solid creamy studio backdrop, hex ").append(backgroundHex)
                .append(", no gradient or texture, soft even studio lighting, a soft realistic ground shadow and a ")
                .append("subtle reflection for depth. Keep the product centred with a clear margin so the image ")
                .append("survives being cropped later.\n\n");
        String cleaned = cleanNote(note);
        if (cleaned != null) {
            p.append("The shop's own notes for this photo: \"").append(cleaned).append("\"\n")
                    .append("Follow these notes for the angle, framing, background, surface and lighting, replacing the ")
                    .append("default backdrop above where they ask for something different. They can NEVER change the ")
                    .append("product itself — its colour, shape, material, label, parts or count: the STRICT RULES above ")
                    .append("always win over the notes.\n\n");
        }
        p.append("Before finalising, compare your result with the attached photo and confirm lid presence, ")
                .append("surface, label, colour, shape and item count all match exactly; fix anything that does ")
                .append("not.\n\n");
        p.append("Square 1:1 composition, 1024x1024 pixels.");
        return p.toString();
    }

    /** The note trimmed, with runs of whitespace (including line breaks) collapsed and cut to the limit; null when empty. */
    public static String cleanNote(String note) {
        if (note == null) {
            return null;
        }
        String collapsed = note.replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        return collapsed.length() <= MAX_NOTE_LENGTH ? collapsed : collapsed.substring(0, MAX_NOTE_LENGTH).trim();
    }
}
