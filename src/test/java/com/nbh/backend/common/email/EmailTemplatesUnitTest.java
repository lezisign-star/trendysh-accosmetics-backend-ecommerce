package com.nbh.backend.common.email;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplatesUnitTest {

    @Test
    void otpCodeEmbedsEveryDynamicValueAndIsWellFormed() {
        String html = EmailTemplates.otpCode(
                "https://example.com/trendysh-logo.png", "Njeri", "Someone asked to reset your password.", "482913", 10);

        assertThat(html).startsWith("<!DOCTYPE html>").contains("</html>");
        assertThat(html).contains("https://example.com/trendysh-logo.png");
        assertThat(html).contains("Njeri");
        assertThat(html).contains("Someone asked to reset your password.");
        assertThat(html).contains("482913");
        assertThat(html).contains("Expires in 10 minutes");
        // Every literal "%" in the markup (width="100%") must have survived .formatted() unescaped.
        assertThat(html).contains("width=\"100%\"");
        // The footer names the shops (the owner's list, 2026-10-05), not the old Kilimani address.
        assertThat(html).contains("Superior Arcade, Shop B33 and B10", "Simara Mall, Shop GS 07", "Junction, Stalls D5");
        assertThat(html).doesNotContain("Kilimani");
    }

    @Test
    void escapesHtmlSpecialCharactersInDynamicText() {
        String html = EmailTemplates.otpCode(
                "https://example.com/logo.png", "<script>alert(1)</script>", "Ben & Jerry's \"Salon\"", "123456", 10);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("Ben &amp; Jerry's"); // apostrophe is not HTML-special in text content
        assertThat(html).contains("&quot;Salon&quot;");
    }
}
