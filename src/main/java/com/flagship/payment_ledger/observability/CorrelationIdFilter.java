package com.flagship.payment_ledger.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that extracts or generates correlation IDs for HTTP requests.
 *
 * Phase 7: Observability
 *
 * This filter:
 * 1. Extracts correlation ID from incoming request header (X-Correlation-ID)
 * 2. Generates a new one if not present
 * 3. Sets it in MDC for logging
 * 4. Adds it to the response header
 * 5. Cleans up after request completes
 *
 * Order: HIGHEST_PRECEDENCE ensures this runs before all other filters.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // Extract or generate correlation ID
            String correlationId = extractOrGenerateCorrelationId(request);

            // Set in thread-local context
            CorrelationContext.setCorrelationId(correlationId);

            // Set in MDC for logging
            MDC.put(CorrelationContext.CORRELATION_ID_MDC_KEY, correlationId);

            // Add to response header for client correlation
            response.setHeader(CorrelationContext.CORRELATION_ID_HEADER, correlationId);

            // Continue filter chain
            filterChain.doFilter(request, response);

        } finally {
            // Clean up thread-local and MDC
            CorrelationContext.clear();
            MDC.remove(CorrelationContext.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationContext.PAYMENT_ID_MDC_KEY);
            MDC.remove(CorrelationContext.ACCOUNT_ID_MDC_KEY);
        }
    }

    private String extractOrGenerateCorrelationId(HttpServletRequest request) {
        String correlationId = request.getHeader(CorrelationContext.CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationContext.generateCorrelationId();
        }

        return correlationId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Don't filter actuator endpoints to reduce noise
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }
}
