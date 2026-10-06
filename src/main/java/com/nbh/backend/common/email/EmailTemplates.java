package com.nbh.backend.common.email;

import java.util.List;

/**
 * Bulletproof-HTML email bodies: table layout, inline styles only — nothing structural relies on a
 * {@code <style>} block, since many clients (Gmail included) strip it. Colors are the storefront's
 * actual brand palette (see {@code src/styles.css} in the frontend) sampled to hex, since email
 * clients don't support {@code oklch()}.
 */
public final class EmailTemplates {

    private EmailTemplates() {}

    private static final String PRIMARY = "#bd0087"; // fuchsia
    private static final String LEAF_FOREGROUND = "#ffe2ee"; // the code box's background
    private static final String FOREGROUND = "#261c21";
    private static final String MUTED = "#71656d";
    private static final String BORDER = "#e8d9e1";
    private static final String BACKGROUND = "#fffafc";

    /** The footer every email carries: what the shop is, and where its three shops are. */
    private static final String FOOTER = "Nairobi's wholesale supplier of professional beauty products and salon equipment<br />"
            + "Superior Arcade, Shop B33 and B10 &middot; Simara Mall, Shop GS 07 &middot; Junction, Stalls D5";

    /**
     * A 6-digit verification code email: branded header with the logo, a card with the code in a
     * highlighted box, and a footer. {@code logoUrl} must be an absolute URL — a relative path
     * doesn't resolve inside an email client, which has no notion of "this site."
     */
    public static String otpCode(String logoUrl, String greetingName, String introLine, String code, int expiryMinutes) {
        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background:%s;font-family:Arial,Helvetica,sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:%s;padding:32px 16px;">
                <tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:480px;">
                <tr><td align="center" style="padding-bottom:24px;">
                <img src="%s" width="56" height="56" alt="Trendysh" style="display:block;border:0;" />
                <div style="margin-top:8px;font-size:18px;font-weight:700;color:%s;">Trendysh Accosmetic</div>
                </td></tr>
                <tr><td style="background:#ffffff;border:1px solid %s;border-radius:16px;padding:32px;">
                <p style="margin:0 0 4px;font-size:15px;color:%s;">Hi %s,</p>
                <p style="margin:0 0 24px;font-size:14px;color:%s;line-height:1.5;">%s</p>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:%s;border-radius:12px;">
                <tr><td align="center" style="padding:20px;">
                <div style="font-size:34px;font-weight:700;letter-spacing:8px;color:%s;">%s</div>
                </td></tr>
                </table>
                <p style="margin:20px 0 0;font-size:13px;color:%s;">Expires in %d minutes. If you didn't ask for this, you can ignore this email — nothing changes.</p>
                </td></tr>
                <tr><td align="center" style="padding-top:24px;font-size:12px;color:%s;line-height:1.6;">
                %s
                </td></tr>
                </table>
                </td></tr>
                </table>
                </body>
                </html>
                """.formatted(
                BACKGROUND, BACKGROUND,
                logoUrl, FOREGROUND,
                BORDER, FOREGROUND, escapeHtml(greetingName), MUTED, escapeHtml(introLine),
                LEAF_FOREGROUND, PRIMARY, code,
                MUTED, expiryMinutes,
                MUTED, FOOTER);
    }

    /** One label and value in the highlighted box ("Business number", "400200"). */
    public record Fact(String label, String value) {}

    /** One line of what was ordered: the words, and what it cost already formatted ("KSh 2,400"). */
    public record Line(String description, String amountText) {}

    /**
     * An email about an order: the logo, a card with a greeting, a headline and a short paragraph, then (each optional)
     * a highlighted box of facts, the items with their total, a button, and a small note; the shop's footer below.
     * Every piece of text is escaped, since names and addresses come from customers. {@code buttonUrl} must be absolute.
     */
    public static String orderMessage(String logoUrl, String greetingName, String headline, String intro,
                                      List<Fact> facts, List<Line> lines, String totalLabel, String totalText,
                                      String buttonLabel, String buttonUrl, String note) {
        StringBuilder card = new StringBuilder();
        card.append("<p style=\"margin:0 0 4px;font-size:15px;color:").append(FOREGROUND).append(";\">Hi ")
                .append(escapeHtml(greetingName)).append(",</p>");
        card.append("<p style=\"margin:0 0 12px;font-size:20px;font-weight:700;color:").append(FOREGROUND).append(";\">")
                .append(escapeHtml(headline)).append("</p>");
        card.append("<p style=\"margin:0 0 20px;font-size:14px;color:").append(MUTED).append(";line-height:1.5;\">")
                .append(escapeHtml(intro)).append("</p>");

        if (facts != null && !facts.isEmpty()) {
            card.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:")
                    .append(LEAF_FOREGROUND).append(";border-radius:12px;margin:0 0 20px;\"><tr><td style=\"padding:16px 20px;\">");
            for (Fact fact : facts) {
                card.append("<div style=\"margin:0 0 10px;\"><div style=\"font-size:11px;letter-spacing:1px;text-transform:uppercase;color:")
                        .append(MUTED).append(";\">").append(escapeHtml(fact.label()))
                        .append("</div><div style=\"font-size:18px;font-weight:700;color:").append(PRIMARY).append(";\">")
                        .append(escapeHtml(fact.value())).append("</div></div>");
            }
            card.append("</td></tr></table>");
        }

        if (lines != null && !lines.isEmpty()) {
            card.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 4px;\">");
            for (Line line : lines) {
                card.append("<tr><td style=\"padding:8px 0;border-top:1px solid ").append(BORDER)
                        .append(";font-size:14px;color:").append(FOREGROUND).append(";\">")
                        .append(escapeHtml(line.description()))
                        .append("</td><td align=\"right\" style=\"padding:8px 0 8px 12px;border-top:1px solid ").append(BORDER)
                        .append(";font-size:14px;color:").append(FOREGROUND).append(";white-space:nowrap;\">")
                        .append(escapeHtml(line.amountText())).append("</td></tr>");
            }
            if (totalText != null) {
                card.append("<tr><td style=\"padding:10px 0 0;border-top:2px solid ").append(BORDER)
                        .append(";font-size:15px;font-weight:700;color:").append(FOREGROUND).append(";\">")
                        .append(escapeHtml(totalLabel))
                        .append("</td><td align=\"right\" style=\"padding:10px 0 0 12px;border-top:2px solid ").append(BORDER)
                        .append(";font-size:15px;font-weight:700;color:").append(PRIMARY).append(";white-space:nowrap;\">")
                        .append(escapeHtml(totalText)).append("</td></tr>");
            }
            card.append("</table>");
        }

        if (buttonLabel != null && buttonUrl != null) {
            card.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:24px 0 0;\"><tr>"
                    + "<td style=\"background:").append(PRIMARY).append(";border-radius:10px;\"><a href=\"")
                    .append(escapeHtml(buttonUrl)).append("\" style=\"display:inline-block;padding:14px 28px;font-size:15px;"
                            + "font-weight:700;color:#ffffff;text-decoration:none;\">")
                    .append(escapeHtml(buttonLabel)).append("</a></td></tr></table>");
        }

        if (note != null && !note.isBlank()) {
            card.append("<p style=\"margin:20px 0 0;font-size:13px;color:").append(MUTED).append(";line-height:1.5;\">")
                    .append(escapeHtml(note)).append("</p>");
        }

        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background:%s;font-family:Arial,Helvetica,sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:%s;padding:32px 16px;">
                <tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;">
                <tr><td align="center" style="padding-bottom:24px;">
                <img src="%s" width="56" height="56" alt="Trendysh" style="display:block;border:0;" />
                <div style="margin-top:8px;font-size:18px;font-weight:700;color:%s;">Trendysh Accosmetic</div>
                </td></tr>
                <tr><td style="background:#ffffff;border:1px solid %s;border-radius:16px;padding:32px;">
                %s
                </td></tr>
                <tr><td align="center" style="padding-top:24px;font-size:12px;color:%s;line-height:1.6;">
                %s
                </td></tr>
                </table>
                </td></tr>
                </table>
                </body>
                </html>
                """.formatted(BACKGROUND, BACKGROUND, escapeHtml(logoUrl), FOREGROUND, BORDER, card, MUTED, FOOTER);
    }

    /** Only the characters that can actually break this template's markup — not a general-purpose sanitizer. */
    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
