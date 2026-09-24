package com.nbh.backend.payment.coop;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Co-operative Bank Instant Notification Service (INS) payload, shaped from the schema in the bank's public
 * INSSimulation specification (14 required fields). NOT yet confirmed against a real production notification:
 * every field is nullable here and unknown fields are ignored, so a shape difference degrades to "ignored and
 * logged" rather than an error the bank would retry forever.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoopInsNotification(
        @JsonProperty("MessageReference") String messageReference,
        @JsonProperty("MessageDateTime") String messageDateTime,
        @JsonProperty("ServiceName") String serviceName,
        @JsonProperty("NotificationCode") String notificationCode,
        @JsonProperty("PaymentRef") String paymentRef,
        @JsonProperty("AccountNumber") String accountNumber,
        @JsonProperty("Amount") BigDecimal amount,
        @JsonProperty("TransactionDate") String transactionDate,
        @JsonProperty("EventType") String eventType,
        @JsonProperty("Currency") String currency,
        @JsonProperty("ExchangeRate") BigDecimal exchangeRate,
        @JsonProperty("Narration") String narration,
        @JsonProperty("CustMemo") CustMemo custMemo,
        @JsonProperty("ValueDate") String valueDate,
        @JsonProperty("EntryDate") String entryDate,
        @JsonProperty("TransactionId") String transactionId) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustMemo(
            @JsonProperty("CustMemoLine1") String line1,
            @JsonProperty("CustMemoLine2") String line2,
            @JsonProperty("CustMemoLine3") String line3) {}
}
