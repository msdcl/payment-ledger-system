package com.flagship.payment_ledger.payment;

import com.flagship.payment_ledger.payment.dto.CreatePaymentRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Scheduled job that creates 50 payments every 10 minutes.
 * Generates continuous data for Grafana dashboard verification.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentLoadRunner {

    private static final int TOTAL_PAYMENTS = 50;

    private final PaymentController paymentController;

    private final Random random = new Random();

    private final List<UUID> fromAccounts = List.of(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            UUID.fromString("44444444-4444-4444-4444-444444444444"),
            UUID.fromString("55555555-5555-5555-5555-555555555555")
    );

    private final List<UUID> toAccounts = List.of(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
            UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    );

    private final CurrencyCode[] currencies = CurrencyCode.values();

    @Scheduled(fixedRate = 600000)
    public void createPayments() {
        log.info("Scheduled job started - creating {} payments", TOTAL_PAYMENTS);

        int success = 0;
        int failed = 0;

        for (int i = 1; i <= TOTAL_PAYMENTS; i++) {
            try {
                String idempotencyKey = UUID.randomUUID().toString();
                BigDecimal amount = randomAmount();
                CurrencyCode currency = currencies[random.nextInt(currencies.length)];
                UUID from = fromAccounts.get(random.nextInt(fromAccounts.size()));
                UUID to = toAccounts.get(random.nextInt(toAccounts.size()));

                CreatePaymentRequest request = new CreatePaymentRequest(amount, currency.name(), from, to);
                ResponseEntity<?> response = paymentController.createPayment(request, idempotencyKey);

                log.info("[{}/{}] Created payment | {} {} | status={}",
                        i, TOTAL_PAYMENTS, amount, currency, response.getStatusCode().value());
                success++;
            } catch (Exception e) {
                log.error("[{}/{}] Failed: {}", i, TOTAL_PAYMENTS, e.getMessage());
                failed++;
            }
        }

        log.info("Scheduled job complete: {} succeeded, {} failed out of {}", success, failed, TOTAL_PAYMENTS);
    }

    private BigDecimal randomAmount() {
        double amount = 10 + (random.nextDouble() * 990);
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }
}
