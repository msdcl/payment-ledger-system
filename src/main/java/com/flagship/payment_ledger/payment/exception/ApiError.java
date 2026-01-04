package com.flagship.payment_ledger.payment.exception;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * Standard API error response.
 * 
 * Phase 3: Consistent error response format.
 */
@Value
@Builder
public class ApiError {
    String error;
    String message;
    Map<String, String> details;
    Instant timestamp;
}
