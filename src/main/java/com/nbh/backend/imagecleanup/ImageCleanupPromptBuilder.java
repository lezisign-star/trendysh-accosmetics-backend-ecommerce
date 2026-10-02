package com.nbh.backend.imagecleanup;

import org.springframework.stereotype.Component;

/**
 * Builds the Gemini edit instruction for one shot of a product. The real photo is treated as ground truth: the
 * allowed edits are listed first, then an itemised don't-add list (the failure modes seen when this approach
 * was tried on another catalogue: invented lids, textures, labels, extra units). Each shot states its own
 * frame-fill target — descriptive wording alone returned near-identical crops. The logo is NOT drawn by the
 * model (image models garble small precise marks); {@link WatermarkService} composites it afterwards.
 */
@Component
public class ImageCleanupPromptBuilder {

    /** Up to six distinct views. The first stays closest to the photo; later ones extrapolate more. */
    private static final String[] SHOTS = {
            "Hero shot: keep exactly the same angle and pose as the reference photo — do not invent a new angle, "
                    + "just clean it up. The product occupies no more than 60% of the frame's width and height, "
                    + "generous margin on every side.",
            "Detail shot: the exact same camera angle and pose as the reference — do not rotate or change the "
                    + "viewpoint — but crop in MUCH closer so the label, texture and surface material read clearly. "
                    + "Every visible detail must match the reference where the crop overlaps. The product fills at "
                    + "least 85% of the frame, dramatically closer than a normal product shot.",
            "Wide shot: a three-quarter view from the opposite side to the reference photo, framed WIDER so the whole "
                    + "silhouette and surrounding space show. Extrapolate this angle, keeping the real colour, shape "
                    + "and any visible label exactly consistent with the photo. The product occupies no more than "
                    + "50% of the frame, with clearly more empty space than the hero shot.",
            "Front view: straight-on and centred, camera at the product's mid-height, medium framing (product about "
                    + "65% of the frame). Extrapolate this view from the reference, keeping colour, shape and any "
                    + "visible label consistent.",
            "Elevated view: camera raised about 45 degrees looking down at the product, medium framing (product "
                    + "about 60% of the frame). Extrapolate this view, keeping colour, shape and any visible label "
                    + "consistent with the reference.",
            "Side view: a clean side profile, product about 60% of the frame, so the depth and shape read clearly. "
                    + "Extrapolate this view, keeping colour, shape and any visible label consistent with the "
                    + "reference.",
    };

    public int maxShots() {
        return SHOTS.length;
    }

    public String build(String productName, int shotIndex, String backgroundHex) {
        String shot = SHOTS[Math.floorMod(shotIndex, SHOTS.length)];
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
        p.append("Make it a professional studio catalogue photo. ").append(shot)
                .append(" Use a seamless solid creamy studio backdrop, hex ").append(backgroundHex)
                .append(", no gradient or texture, soft even studio lighting, a soft realistic ground shadow and a ")
                .append("subtle reflection for depth. Keep the product centred with a clear margin so the image ")
                .append("survives being cropped later.\n\n");
        p.append("Before finalising, compare your result with the attached photo and confirm lid presence, ")
                .append("surface, label, colour, shape and item count all match exactly; fix anything that does ")
                .append("not.\n\n");
        p.append("Square 1:1 composition, 1024x1024 pixels.");
        return p.toString();
    }
}
