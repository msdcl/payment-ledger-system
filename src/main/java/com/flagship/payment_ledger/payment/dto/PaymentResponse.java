package com.flagship.payment_ledger.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flagship.payment_ledger.payment.Payment;
import com.flagship.payment_ledger.payment.PaymentStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for payment operations.
 * 
 * Phase 3: REST API response model.
 */
@Value
@Builder
public class PaymentResponse {
    
    @JsonProperty("id")
    UUID id;
    
    @JsonProperty("amount")
    BigDecimal amount;
    
    @JsonProperty("currency")
    String currency;
    
    @JsonProperty("from_account_id")
    UUID fromAccountId;
    
    @JsonProperty("to_account_id")
    UUID toAccountId;
    
    @JsonProperty("status")
    PaymentStatus status;
    
    @JsonProperty("failure_reason")
    String failureReason;
    
    @JsonProperty("created_at")
    Instant createdAt;
    
    @JsonProperty("updated_at")
    Instant updatedAt;
    
    /**
     * Creates a PaymentResponse from a domain Payment object.
     */
    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
            .id(payment.getId())
            .amount(payment.getAmount())
            .currency(payment.getCurrency().name()) // Convert CurrencyCode enum to String for JSON
            .fromAccountId(payment.getFromAccountId())
            .toAccountId(payment.getToAccountId())
            .status(payment.getStatus())
            .failureReason(payment.getFailureReason())
            .createdAt(payment.getCreatedAt())
            .updatedAt(payment.getUpdatedAt())
            .build();
    }
}
