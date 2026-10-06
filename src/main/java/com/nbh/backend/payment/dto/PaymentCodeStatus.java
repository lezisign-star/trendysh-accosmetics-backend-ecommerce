package com.nbh.backend.payment.dto;

import com.nbh.backend.payment.entity.PaymentClaim;

import java.util.Collection;

/**
 * Where an order stands on the M-PESA code the customer has to enter to finish placing it. The order itself exists
 * from the moment it is placed (so staff can see it and chase it if the customer wanders off to pay and never comes
 * back); this says whether the code has arrived.
 *
 * <ul>
 *   <li>{@link #NONE}: no code has been sent for this order yet.</li>
 *   <li>{@link #PENDING}: a code is waiting for staff to check it against the statement.</li>
 *   <li>{@link #REJECTED}: every code sent so far was turned down, so the customer needs to send another.</li>
 * </ul>
 */
public enum PaymentCodeStatus {
    NONE, PENDING, REJECTED;

    /**
     * A waiting code beats a rejected one, and a rejected one beats nothing. An approved claim means the payment
     * went through, which leaves the order's unpaid stage altogether; it is counted as received so a stray one can
     * never make a paid order look as if it still needs a code.
     */
    public static PaymentCodeStatus of(Collection<PaymentClaim.ClaimStatus> claimStatuses) {
        boolean rejected = false;
        for (PaymentClaim.ClaimStatus status : claimStatuses) {
            if (status == PaymentClaim.ClaimStatus.REJECTED) {
                rejected = true;
            } else {
                return PENDING;
            }
        }
        return rejected ? REJECTED : NONE;
    }
}
