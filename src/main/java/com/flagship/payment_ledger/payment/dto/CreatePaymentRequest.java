package com.flagship.payment_ledger.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for creating a payment.
 * 
 * Phase 3: REST API request model with validation.
 */
@Value
public class CreatePaymentRequest {
    
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @JsonProperty("amount")
    BigDecimal amount;
    
    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code")
    @JsonProperty("currency")
    String currency;
    
    @NotNull(message = "From account ID is required")
    @JsonProperty("from_account_id")
    UUID fromAccountId;
    
    @NotNull(message = "To account ID is required")
    @JsonProperty("to_account_id")
    UUID toAccountId;
}
