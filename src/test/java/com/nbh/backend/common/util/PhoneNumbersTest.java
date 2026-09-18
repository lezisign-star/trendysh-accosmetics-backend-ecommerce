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

    @Test
    void sameNumberComparesAcrossSpellingsAndRejectsDifferentOrBlankNumbers() {
        assertThat(PhoneNumbers.sameNumber("0720000111", "+254720000111")).isTrue();
        assertThat(PhoneNumbers.sameNumber("0720000111", "0720000112")).isFalse();
        assertThat(PhoneNumbers.sameNumber("", "")).isFalse();
        assertThat(PhoneNumbers.sameNumber(null, "0720000111")).isFalse();
    }
}
