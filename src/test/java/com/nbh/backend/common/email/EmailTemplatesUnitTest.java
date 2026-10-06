package com.nbh.backend.common.email;

import com.nbh.backend.common.email.EmailTemplates.Fact;
import com.nbh.backend.common.email.EmailTemplates.Line;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void orderMessageCarriesFactsLinesTotalButtonAndNoteAndEscapesEverythingTheCustomerTyped() {
        String html = EmailTemplates.orderMessage(
                "https://example.com/trendysh-logo.png", "Njeri", "Your order is saved, one more step",
                "Pay with M-PESA Paybill, then type the code.",
                List.of(new Fact("Business number", "400200"), new Fact("Account number", "NJERI <B>")),
                List.of(new Line("Gel Polish (Ruby Red) × 2", "KSh 1,300"), new Line("TumaBoda delivery", "KSh 250")),
                "Total", "KSh 1,550", "Pay and enter my M-PESA code", "https://shop.example/pay/SO-1?ref=ABC&x=1",
                "Questions? WhatsApp us.");

        assertThat(html).startsWith("<!DOCTYPE html>").contains("</html>");
        assertThat(html).contains("Hi Njeri,", "Your order is saved, one more step", "Business number", "400200");
        assertThat(html).contains("Gel Polish (Ruby Red) × 2", "KSh 1,300", "TumaBoda delivery", "KSh 250", "KSh 1,550");
        // A button is a link the shopper can follow, and its URL is escaped like any other attribute.
        assertThat(html).contains("href=\"https://shop.example/pay/SO-1?ref=ABC&amp;x=1\"", "Pay and enter my M-PESA code");
        assertThat(html).contains("Questions? WhatsApp us.");
        // The account text came from the customer: it is text in the email, never markup.
        assertThat(html).contains("NJERI &lt;B&gt;").doesNotContain("<B>");
        // Every literal "%" in the markup must have survived .formatted(), and the shops' footer is there.
        assertThat(html).contains("width=\"100%\"");
        assertThat(html).contains("Superior Arcade, Shop B33 and B10", "Simara Mall, Shop GS 07", "Junction, Stalls D5");
    }

    @Test
    void orderMessageWithNothingOptionalStillMakesAWholeEmail() {
        String html = EmailTemplates.orderMessage("https://example.com/logo.png", "there", "Payment received",
                "Thank you.", List.of(), List.of(), "Total", null, null, null, null);

        assertThat(html).startsWith("<!DOCTYPE html>").contains("</html>").contains("Hi there,", "Payment received", "Thank you.");
        assertThat(html).doesNotContain("<a href");
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
