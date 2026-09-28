package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.payment.entity.PaymentSettings;
import com.nbh.backend.payment.repository.PaymentSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What the customer types as the paybill account, and the merchant name their phone shows. */
class BankPaymentSettingsTest {

    private final PaymentSettings row = new PaymentSettings();

    private BankPaymentSettings settingsWithEnvTemplate(String envTemplate) {
        PaymentSettingsRepository repository = mock(PaymentSettingsRepository.class);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(row));
        return new BankPaymentSettings(repository, BankPaymentSettings.CollectionMode.BANK, 45, envTemplate);
    }

    @Test
    void theCustomersNameIsTheAccountByDefaultUpperCasedAndStrippedOfPunctuation() {
        var settings = settingsWithEnvTemplate("<name>");

        assertThat(settings.paybillAccountFor("Njeri O'Connor-Kamau", "0720000111", "SO-1", "ABC1234"))
                .isEqualTo("NJERI O CONNOR KAMAU");
    }

    @Test
    void aLongNameIsCutToTwentyCharactersBecauseManyPaybillsCapTheAccountField() {
        var settings = settingsWithEnvTemplate("<name>");

        assertThat(settings.paybillAccountFor("Wanjiku Mwangi Kariuki Nyambura", null, "SO-1", "R"))
                .isEqualTo("WANJIKU MWANGI KARIU");
    }

    @Test
    void aFixedPrefixCanSitInFrontOfAnyPlaceholder() {
        var settings = settingsWithEnvTemplate("01100123#<name>");

        assertThat(settings.paybillAccountFor("Njeri Test", "0720000111", "SO-1", "ABC1234"))
                .isEqualTo("01100123#NJERI TEST");
    }

    @Test
    void phoneOrderAndReferencePlaceholdersResolveToTheRealValues() {
        var settings = settingsWithEnvTemplate("<phone>-<order>-<ref>");

        assertThat(settings.paybillAccountFor("Njeri", "0720 000 111", "SO-20260928-000001", "ABC1234"))
                .isEqualTo("254720000111-SO-20260928-000001-ABC1234");
    }

    @Test
    void aPlaceholderWithNoValueResolvesToNothingNotALiteralTag() {
        // With no order number or reference to fall back on, "nothing" is what is left — never "<name>".
        assertThat(BankPaymentSettings.resolve("<name>", null, "0720000111", "", "")).isEmpty();
        assertThat(BankPaymentSettings.resolve("<name>-<order>", null, "0720000111", "SO-1", "R")).isEqualTo("-SO-1");
    }

    @Test
    void anOrderWhoseNameIsMissingIsToldToTypeItsOrderNumberInsteadOfNothing() {
        var settings = settingsWithEnvTemplate("<name>");

        assertThat(settings.paybillAccountFor(null, "0720000111", "SO-1", "R")).isEqualTo("SO-1");
    }

    @Test
    void anAdminSetTemplateWinsOverTheEnvironmentFallback() {
        var settings = settingsWithEnvTemplate("<ref>");
        assertThat(settings.paybillAccountTemplate()).isEqualTo("<ref>");

        row.setPaybillAccountTemplate("<name>");

        assertThat(settings.paybillAccountTemplate()).isEqualTo("<name>");
    }

    @Test
    void theMerchantNameIsWhatAdminEnteredOrEmpty() {
        var settings = settingsWithEnvTemplate("<name>");
        assertThat(settings.paybillMerchantName()).isEmpty();

        row.setPaybillMerchantName("TRENDYSH ACCOSMETIC");

        assertThat(settings.paybillMerchantName()).isEqualTo("TRENDYSH ACCOSMETIC");
    }

    @Test
    void thePreviewIsExactlyWhatTheCustomerWillTypeWhenTheTemplateUsesOnlyNameAndPhone() {
        var settings = settingsWithEnvTemplate("<name>");

        var preview = settings.previewAccount("Njeri Test", "0720000111");

        assertThat(preview.accountNumber()).isEqualTo("NJERI TEST");
        assertThat(preview.finalValue()).isTrue();
    }

    @Test
    void thePreviewNamesWhatWillAppearWhenTheTemplateNeedsTheOrderOrReference() {
        var settings = settingsWithEnvTemplate("01100123#<ref>");

        var preview = settings.previewAccount("Njeri Test", "0720000111");

        assertThat(preview.accountNumber()).isEqualTo("01100123#YOUR PAYMENT REFERENCE");
        assertThat(preview.finalValue()).isFalse();
    }

    @Test
    void aNameWithNoLatinLettersFallsBackToTheOrderNumberRatherThanAnEmptyAccount() {
        var settings = settingsWithEnvTemplate("<name>");

        assertThat(settings.paybillAccountFor("李小龍", "0720000111", "SO-20260928-000123", "K7M2QX"))
                .isEqualTo("SO-20260928-000123");
        assertThat(settings.paybillAccountFor("Njeri Test", "0720000111", "SO-20260928-000123", "K7M2QX"))
                .as("an ordinary name is untouched").isEqualTo("NJERI TEST");
    }

    @Test
    void theFallbackUsesTheReferenceIfThereIsNoOrderNumber() {
        assertThat(BankPaymentSettings.orderNumberIfBlank("", null, "K7M2QX")).isEqualTo("K7M2QX");
        assertThat(BankPaymentSettings.orderNumberIfBlank("", "  ", "K7M2QX")).isEqualTo("K7M2QX");
        assertThat(BankPaymentSettings.orderNumberIfBlank("", null, null)).isEmpty();
        assertThat(BankPaymentSettings.orderNumberIfBlank("01100123#", "SO-1", "K7")).isEqualTo("01100123#");
    }

    @Test
    void aMistypedPlaceholderIsRejectedBeforeItCanReachCustomers() {
        assertThatThrownBy(() -> BankPaymentSettings.requireKnownPlaceholders("01100123#<nmae>"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("<nmae>");
        BankPaymentSettings.requireKnownPlaceholders("01100123#<name>-<phone>-<order>-<ref>");
    }
}
