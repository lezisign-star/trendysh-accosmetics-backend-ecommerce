package com.nbh.backend.imagecleanup;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The instruction for writing a product's text from its real photo. It states the limits as numbers (the server cuts
 * anything longer anyway), and the rule that matters most: write only what the photo, the name and the staff note
 * support. A shop selling beauty products can't have an AI inventing ingredients or results.
 */
@Component
public class DescriptionPromptBuilder {

    public String build(String productName, List<String> categories, String note) {
        StringBuilder p = new StringBuilder();
        p.append("You write product text for Trendysh, a wholesale beauty-supplies shop in Nairobi, Kenya that sells to ")
                .append("salons, studios and shops. The attached image is a real photo of the product \"")
                .append(productName).append("\"");
        if (categories != null && !categories.isEmpty()) {
            p.append(" (category: ").append(String.join(", ", categories)).append(")");
        }
        p.append(".\n\n");
        p.append("WRITE ONLY WHAT IS SUPPORTED. Use only what you can see in the photo, what the name says, and the shop's ")
                .append("own note below. Never invent a size, quantity, brand, shade count, ingredient, ")
                .append("certification, result, claim, medical or safety statement, price, or delivery promise. If you ")
                .append("are not sure of something, leave it out.\n\n");
        p.append("Fields (every character limit is firm):\n")
                .append("- shortDescription: one plain line for a product card, at most ")
                .append(DescriptionCaps.SHORT_DESCRIPTION).append(" characters.\n")
                .append("- description: 2 to 3 short sentences for the product page, at most ")
                .append(DescriptionCaps.DESCRIPTION).append(" characters. What it is and who uses it.\n")
                .append("- highlights: up to ").append(DescriptionCaps.HIGHLIGHTS_COUNT)
                .append(" short bullet points, each at most ").append(DescriptionCaps.HIGHLIGHT)
                .append(" characters, each something genuinely visible or stated.\n")
                .append("- ingredients: at most ").append(DescriptionCaps.INGREDIENTS)
                .append(" characters. Fill ONLY if the ingredients are readable on the label in the photo or written in ")
                .append("the shop's note; otherwise an empty string.\n")
                .append("- howToUse: at most ").append(DescriptionCaps.HOW_TO_USE)
                .append(" characters. Fill ONLY if instructions are readable on the label or written in the shop's note; ")
                .append("otherwise an empty string.\n")
                .append("- seoTitle: for a search result, at most ").append(DescriptionCaps.SEO_TITLE)
                .append(" characters, led by the product name. Do not add the shop's name.\n")
                .append("- seoDescription: for a search result, at most ").append(DescriptionCaps.SEO_DESCRIPTION)
                .append(" characters.\n\n");
        p.append("Style: plain, warm, professional English (British spelling). No emojis, no ALL CAPS, no exclamation ")
                .append("marks, no hype words like \"best\" or \"amazing\", no mention of price or of the photo itself.\n\n");
        String cleaned = ImageCleanupPromptBuilder.cleanNote(note);
        if (cleaned != null) {
            p.append("The shop's own note about this product (treat as true): \"").append(cleaned).append("\"\n\n");
        }
        p.append("Answer with the JSON object only.");
        return p.toString();
    }
}
