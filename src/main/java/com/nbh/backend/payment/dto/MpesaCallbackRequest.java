package com.nbh.backend.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Safaricom's documented Daraja v1 STK callback envelope:
 * {@code {"Body":{"stkCallback":{"CheckoutRequestID":"...","ResultCode":0,...}}}}.
 * Field names are bound explicitly via {@link JsonProperty} because Safaricom's
 * own JSON uses PascalCase, not Java's camelCase convention. Replaces the raw
 * {@code Map<String,Object>} + unchecked-cast binding that let a missing key
 * NPE into a 500 (§4 C1) — an unrecognised/missing field here just leaves the
 * corresponding component null, which the controller checks explicitly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MpesaCallbackRequest(@JsonProperty("Body") Body body) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(@JsonProperty("stkCallback") StkCallback stkCallback) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StkCallback(
            @JsonProperty("MerchantRequestID") String merchantRequestId,
            @JsonProperty("CheckoutRequestID") String checkoutRequestId,
            @JsonProperty("ResultCode") Integer resultCode,
            @JsonProperty("ResultDesc") String resultDesc,
            @JsonProperty("CallbackMetadata") CallbackMetadata callbackMetadata
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackMetadata(@JsonProperty("Item") List<Item> item) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(@JsonProperty("Name") String name, @JsonProperty("Value") Object value) {}
}
