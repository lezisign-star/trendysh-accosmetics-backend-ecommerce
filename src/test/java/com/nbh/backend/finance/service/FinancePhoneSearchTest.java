package com.nbh.backend.finance.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger search matches a customer's phone only when what was typed is a phone number. */
class FinancePhoneSearchTest {

    @Test
    void aPhoneNumberAsStaffTypeItMatchesTheStoredNumberAnywhere() {
        assertThat(FinanceService.phoneLike("0712 345 678")).isEqualTo("%712345678%");
        assertThat(FinanceService.phoneLike("+254-712-345678")).isEqualTo("%254712345678%");
        assertThat(FinanceService.phoneLike("5678")).isEqualTo("%5678%");
    }

    @Test
    void theDigitsOfAReferenceOrAnOrderNumberNeverMatchAPhone() {
        // the ledger once listed every customer whose phone held "1234" for a search of this M-Pesa code
        assertThat(FinanceService.phoneLike("QW1A2B3C4D")).isEqualTo(FinanceService.NO_PHONE);
        assertThat(FinanceService.phoneLike("NBH-20261023-0042")).isEqualTo(FinanceService.NO_PHONE);
        assertThat(FinanceService.phoneLike("Jane 0712")).isEqualTo(FinanceService.NO_PHONE);
    }

    @Test
    void theSpreadsheetWritesAPhoneAsPeopleDoSoExcelKeepsItsDigits() {
        assertThat(FinanceService.phone("254712345678")).isEqualTo("0712 345 678");
        assertThat(FinanceService.phone("254110222333")).isEqualTo("0110 222 333");
        assertThat(FinanceService.phone("+44 20 7946 0958")).isEqualTo("+44 20 7946 0958");
        assertThat(FinanceService.phone(null)).isNull();
    }

    @Test
    void tooFewDigitsMatchNoPhone() {
        assertThat(FinanceService.phoneLike("07")).isEqualTo(FinanceService.NO_PHONE);
        assertThat(FinanceService.phoneLike("")).isEqualTo(FinanceService.NO_PHONE);
    }
}
