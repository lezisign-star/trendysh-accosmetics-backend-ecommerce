package com.nbh.backend.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published by {@code BankCreditService.record} when money is recorded as received at the bank. {@code
 * matched} is true when it was matched to an order and confirmed that payment on the spot (which publishes
 * its own {@link PaymentConfirmedEvent} — {@code orderNo} is only for the staff dashboard to link to that
 * order, and is null while unmatched); false means staff still have to allocate it.
 */
public record BankCreditRecordedEvent(UUID creditId, BigDecimal amount, boolean matched, String orderNo) {}
