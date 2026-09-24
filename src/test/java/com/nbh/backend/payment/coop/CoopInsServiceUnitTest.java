package com.nbh.backend.payment.coop;

import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.service.BankCreditService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CoopInsServiceUnitTest {

    private final BankCreditService credits = mock(BankCreditService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC);

    private CoopInsService service(boolean autoMatch, String account) {
        return new CoopInsService(credits, new CoopInsSettings("u", "p", account, autoMatch), clock);
    }

    private CoopInsNotification credit(String txn, String amount) {
        return new CoopInsNotification("M1", null, null, null, "PAYREF", "54321987654321", new BigDecimal(amount),
                "20260921165420", "CREDIT", "KES", BigDecimal.ONE, "MPESA",
                new CoopInsNotification.CustMemo("ABC123#XYZ", "", null), null, null, txn);
    }

    @Test
    void autoMatchOffRecordsTheCreditWithoutConfirmingAnything() {
        assertEquals(CoopInsService.Outcome.RECORDED, service(false, "").handle(credit("T1", "500")));
        verify(credits).record(eq("T1"), eq(new BigDecimal("500")), any(), any(),
                eq("MPESA | ABC123#XYZ"), any(), eq(BankCredit.Source.IPN), eq(false));
    }

    @Test
    void autoMatchOnPassesTheFlagThrough() {
        service(true, "").handle(credit("T2", "500"));
        verify(credits).record(eq("T2"), any(), any(), any(), any(), any(), eq(BankCredit.Source.IPN), eq(true));
    }

    @Test
    void theBanksTransactionDateIsReadAsNairobiTime() {
        service(false, "").handle(credit("T3", "500"));
        // 2026-09-21 16:54:20 EAT (UTC+3) = 13:54:20 UTC
        verify(credits).record(eq("T3"), any(), any(), any(), any(),
                eq(Instant.parse("2026-09-21T13:54:20Z")), any(), anyBoolean());
    }

    @Test
    void nonCreditsMissingAmountsAndWrongAccountsAreIgnored() {
        CoopInsNotification debit = new CoopInsNotification("M", null, null, null, null, "54321987654321",
                new BigDecimal("5"), null, "DEBIT", null, null, null, null, null, null, "TD");
        assertEquals(CoopInsService.Outcome.IGNORED, service(true, "").handle(debit));
        assertEquals(CoopInsService.Outcome.IGNORED, service(true, "").handle(credit("T4", "0")));
        assertEquals(CoopInsService.Outcome.IGNORED, service(true, "11111111111111").handle(credit("T5", "500")));
        assertEquals(CoopInsService.Outcome.IGNORED, service(true, "").handle(null));
        verify(credits, never()).record(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aMissingTransactionIdFallsBackToThePaymentRefThenTheMessageReference() {
        CoopInsNotification n = new CoopInsNotification("M9", null, null, null, "PR9", "54321987654321",
                new BigDecimal("10"), null, "CREDIT", null, null, null, null, null, null, null);
        service(true, "").handle(n);
        verify(credits).record(eq("PR9"), any(), any(), any(), any(), any(), any(), anyBoolean());
    }
}
