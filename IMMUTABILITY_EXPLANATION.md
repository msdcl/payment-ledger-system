# How Payment Object is Made Immutable

## Overview

The `Payment` object is immutable, meaning once created, it cannot be modified. Instead of changing the existing object, state transitions create new instances with updated values.

## Immutability Mechanisms

### 1. Lombok `@Value` Annotation

```java
@Value
public class Payment {
    // All fields are automatically final
    UUID id;
    BigDecimal amount;
    // ...
}
```

**What `@Value` does:**
- Makes all fields `final` (cannot be reassigned)
- Generates only getters (no setters)
- Generates `equals()`, `hashCode()`, and `toString()`
- Makes the class `final` (cannot be subclassed)
- Generates an all-args constructor

### 2. All Fields are Final

When you use `@Value`, Lombok automatically makes all fields `final`:

```java
@Value
public class Payment {
    final UUID id;              // Cannot be changed
    final BigDecimal amount;    // Cannot be changed
    final PaymentStatus status; // Cannot be changed
    // ... all fields are final
}
```

**Why this matters:**
- Once a field is assigned, it cannot be reassigned
- Prevents accidental mutations
- Thread-safe (no need for synchronization)

### 3. No Setters

Lombok `@Value` does NOT generate setters. This means:

```java
Payment payment = Payment.create(...);
// payment.setStatus(PaymentStatus.AUTHORIZED); // ❌ This method doesn't exist!
```

**Why no setters:**
- Prevents direct field modification
- Forces use of explicit transition methods
- Ensures state changes go through validation

### 4. State Transitions Return New Instances

Instead of modifying the existing object, transition methods create new instances:

```java
public Payment authorize() {
    // Validation
    if (this.status != PaymentStatus.CREATED) {
        throw new IllegalStateException(...);
    }
    
    // Return NEW instance with updated status
    return new Payment(
        this.id,                    // Same ID
        this.amount,                 // Same amount
        this.currency,               // Same currency
        this.fromAccountId,          // Same from account
        this.toAccountId,            // Same to account
        PaymentStatus.AUTHORIZED,     // NEW status
        this.failureReason,          // Same failure reason
        this.createdAt,              // Same created timestamp
        Instant.now()                // NEW updated timestamp
    );
}
```

**Key points:**
- Original object is unchanged
- New object is created with updated values
- Old object can still be used (if referenced)
- Thread-safe (no shared mutable state)

## Example: How Immutability Works

### Creating a Payment

```java
Payment created = Payment.create(
    UUID.randomUUID(),
    new BigDecimal("100.00"),
    "USD",
    account1Id,
    account2Id
);
// created.status = CREATED
// created.id = some-uuid
// created.updatedAt = 2024-01-01T10:00:00Z
```

### Transitioning State

```java
// OLD WAY (if mutable - DON'T DO THIS):
// created.setStatus(PaymentStatus.AUTHORIZED); // ❌ Would modify existing object

// NEW WAY (immutable - CORRECT):
Payment authorized = created.authorize();
// created.status = STILL CREATED (unchanged!)
// authorized.status = AUTHORIZED (new object)
// authorized.id = SAME as created.id
// authorized.updatedAt = NEW timestamp
```

### Visual Representation

```
Before authorize():
┌─────────────────────┐
│ Payment (created)   │
│ id: abc-123         │
│ status: CREATED     │ ← Original object
│ updatedAt: 10:00    │
└─────────────────────┘

After authorize():
┌─────────────────────┐      ┌─────────────────────┐
│ Payment (created)   │      │ Payment (authorized)│
│ id: abc-123         │      │ id: abc-123         │
│ status: CREATED     │      │ status: AUTHORIZED   │ ← New object
│ updatedAt: 10:00    │      │ updatedAt: 10:05     │
└─────────────────────┘      └─────────────────────┘
   (unchanged)                    (new instance)
```

## Benefits of Immutability

### 1. Thread Safety

```java
// Multiple threads can safely read the same Payment object
Payment payment = ...;
Thread thread1 = new Thread(() -> {
    PaymentStatus status = payment.getStatus(); // Safe - no modification
});
Thread thread2 = new Thread(() -> {
    PaymentStatus status = payment.getStatus(); // Safe - no modification
});
// No synchronization needed!
```

### 2. No Accidental Mutations

```java
Payment payment = ...;
someMethod(payment);
// payment is guaranteed to be unchanged here
// No need to worry about method modifying it
```

### 3. Predictable State

```java
Payment created = Payment.create(...);
Payment authorized = created.authorize();

// created is still in CREATED state
assert created.getStatus() == PaymentStatus.CREATED; // ✅ Always true

// authorized is in AUTHORIZED state
assert authorized.getStatus() == PaymentStatus.AUTHORIZED; // ✅ Always true
```

### 4. Easier Testing

```java
Payment payment = Payment.create(...);
Payment authorized = payment.authorize();

// Original payment unchanged - can test both states
assertEquals(PaymentStatus.CREATED, payment.getStatus());
assertEquals(PaymentStatus.AUTHORIZED, authorized.getStatus());
```

## Comparison: Mutable vs Immutable

### Mutable (Bad - Don't Do This)

```java
// Mutable Payment (hypothetical)
public class MutablePayment {
    private PaymentStatus status;
    
    public void setStatus(PaymentStatus status) {
        this.status = status; // ❌ Can be called anytime, no validation
    }
}

// Usage
Payment payment = new Payment(...);
payment.setStatus(PaymentStatus.SETTLED); // ❌ Bypasses validation!
payment.setStatus(PaymentStatus.CREATED); // ❌ Can go backwards!
```

**Problems:**
- Can bypass validation
- Can transition to invalid states
- Not thread-safe
- Unpredictable behavior

### Immutable (Good - What We Use)

```java
// Immutable Payment
@Value
public class Payment {
    PaymentStatus status;
    
    public Payment authorize() {
        // ✅ Validation enforced
        if (this.status != PaymentStatus.CREATED) {
            throw new IllegalStateException(...);
        }
        // ✅ Returns new instance
        return new Payment(..., PaymentStatus.AUTHORIZED, ...);
    }
}

// Usage
Payment payment = Payment.create(...);
Payment authorized = payment.authorize(); // ✅ Must go through validation
// payment.setStatus(...) // ❌ Doesn't exist - cannot bypass
```

**Benefits:**
- Validation always enforced
- Invalid transitions impossible
- Thread-safe
- Predictable behavior

## How Lombok Generates the Code

When you write:

```java
@Value
public class Payment {
    UUID id;
    PaymentStatus status;
}
```

Lombok generates (conceptually):

```java
public final class Payment {
    private final UUID id;
    private final PaymentStatus status;
    
    // Constructor
    public Payment(UUID id, PaymentStatus status) {
        this.id = id;
        this.status = status;
    }
    
    // Getters only (no setters!)
    public UUID getId() {
        return id;
    }
    
    public PaymentStatus getStatus() {
        return status;
    }
    
    // equals, hashCode, toString
    // ...
}
```

## Summary

The `Payment` object is immutable because:

1. ✅ **`@Value` annotation** - Makes all fields final, generates only getters
2. ✅ **No setters** - Cannot modify fields directly
3. ✅ **State transitions return new instances** - Original object unchanged
4. ✅ **All fields are final** - Cannot be reassigned after construction

This ensures:
- Thread safety
- No accidental mutations
- Predictable behavior
- Validation always enforced
- Invalid state transitions impossible

Immutability is a key principle in domain-driven design and is essential for building correct, reliable systems!
