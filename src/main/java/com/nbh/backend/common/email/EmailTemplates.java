package com.nbh.backend.common.email;

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
                Nairobi's wholesale supplier of professional beauty products and salon equipment<br />
                Wood Avenue Plaza, Kilimani, Nairobi
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
                MUTED);
    }

    /** Only the characters that can actually break this template's markup — not a general-purpose sanitizer. */
    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
