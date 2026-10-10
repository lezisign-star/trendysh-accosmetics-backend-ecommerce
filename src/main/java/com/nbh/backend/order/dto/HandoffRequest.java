package com.nbh.backend.order.dto;

import jakarta.validation.constraints.Size;

/**
 * Where the parcel was handed over and the receipt number, all optional: whatever staff have so far.
 *
 * @param stage     the stage, office or counter it was left at ("Tom Mboya Street, opposite the old Tuskys")
 * @param reference the waybill or receipt number the transporter gave
 * @param note      anything the shopper should know when collecting
 */
public record HandoffRequest(
        @Size(max = 200) String stage,
        @Size(max = 60) String reference,
        @Size(max = 300) String note
) {}
