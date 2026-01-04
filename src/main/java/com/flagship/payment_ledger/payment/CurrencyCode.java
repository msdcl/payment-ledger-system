package com.flagship.payment_ledger.payment;

/**
 * Currency code enum following ISO-4217 standard.
 * 
 * This provides type safety and prevents invalid currency codes
 * from being stored in the database.
 * 
 * Phase 3: Replaces String currency to avoid silent bugs.
 */
public enum CurrencyCode {
    USD, // US Dollar
    EUR, // Euro
    GBP, // British Pound
    INR, // Indian Rupee
    JPY, // Japanese Yen
    // Add more currencies as needed
}
