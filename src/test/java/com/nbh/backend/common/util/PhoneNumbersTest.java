package com.nbh.backend.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    @Test
    void everyCommonSpellingOfOneNumberNormalisesToTheSameValue() {
        assertThat(PhoneNumbers.normalize("0720 000 111")).isEqualTo("254720000111");
        assertThat(PhoneNumbers.normalize("+254 720-000-111")).isEqualTo("254720000111");
        assertThat(PhoneNumbers.normalize("254720000111")).isEqualTo("254720000111");
        assertThat(PhoneNumbers.normalize("(0720) 000111")).isEqualTo("254720000111");
    }

    @Test
    void searchFragmentDropsTheLocalZeroAndIgnoresNonPhoneText() {
        assertThat(PhoneNumbers.searchFragment("0720")).isEqualTo("720");
        assertThat(PhoneNumbers.searchFragment("+254 720")).isEqualTo("254720");
        assertThat(PhoneNumbers.searchFragment("TA-000123")).isNull();
        assertThat(PhoneNumbers.searchFragment("njeri")).isNull();
        assertThat(PhoneNumbers.searchFragment(null)).isNull();
    }

    /** The same cases as the storefront's validators.ts isValidKenyanPhone, so the form and the server agree on what is a number. */
    @Test
    void aKenyanMobileIsAcceptedInEverySpellingThePhoneFormAccepts() {
        assertThat(PhoneNumbers.isValidKenyanMobile("0720 000 111")).isTrue();
        assertThat(PhoneNumbers.isValidKenyanMobile("0720000111")).isTrue();
        assertThat(PhoneNumbers.isValidKenyanMobile("0110-123-456")).isTrue();
        assertThat(PhoneNumbers.isValidKenyanMobile("+254 720 000 111")).isTrue();
        assertThat(PhoneNumbers.isValidKenyanMobile("254720000111")).isTrue();
        assertThat(PhoneNumbers.isValidKenyanMobile("254110123456")).isTrue();
    }

    @Test
    void anythingElseIsNotAMobileNumberStaffCouldRing() {
        assertThat(PhoneNumbers.isValidKenyanMobile(null)).isFalse();
        assertThat(PhoneNumbers.isValidKenyanMobile("")).isFalse();
        assertThat(PhoneNumbers.isValidKenyanMobile("   ")).isFalse();
        assertThat(PhoneNumbers.isValidKenyanMobile("abc")).isFalse();
        assertThat(PhoneNumbers.isValidKenyanMobile("12345")).isFalse();
        assertThat(PhoneNumbers.isValidKenyanMobile("0720 000 11")).isFalse();      // one digit short
        assertThat(PhoneNumbers.isValidKenyanMobile("0720 000 1111")).isFalse();    // one digit long
        assertThat(PhoneNumbers.isValidKenyanMobile("0220 000 111")).isFalse();     // not a mobile prefix
        assertThat(PhoneNumbers.isValidKenyanMobile("+255720000111")).isFalse();    // another country
        assertThat(PhoneNumbers.isValidKenyanMobile("0720000111; DROP TABLE")).isFalse();
    }

    @Test
    void sameNumberComparesAcrossSpellingsAndRejectsDifferentOrBlankNumbers() {
        assertThat(PhoneNumbers.sameNumber("0720000111", "+254720000111")).isTrue();
        assertThat(PhoneNumbers.sameNumber("0720000111", "0720000112")).isFalse();
        assertThat(PhoneNumbers.sameNumber("", "")).isFalse();
        assertThat(PhoneNumbers.sameNumber(null, "0720000111")).isFalse();
    }
}
