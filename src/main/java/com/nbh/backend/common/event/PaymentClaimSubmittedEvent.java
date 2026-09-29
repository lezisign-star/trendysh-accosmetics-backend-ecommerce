package com.nbh.backend.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published by {@code PaymentClaimService.submit} when a customer says they have paid ("I've paid"): staff
 * have to check it against the bank statement. IDs and scalars only, like every event here (see
 * {@link OrderPlacedEvent}).
 */
public record PaymentClaimSubmittedEvent(UUID claimId, UUID paymentRecordId, String orderNo, BigDecimal amountClaimed) {}
