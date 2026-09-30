package com.ai.meetingnotes.controller;

import com.ai.meetingnotes.entity.User;
import com.ai.meetingnotes.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Receives and processes Stripe webhook events.
 *
 * SECURITY: permit-all (no JWT) — Stripe calls this directly.
 * The HMAC-SHA256 signature in the Stripe-Signature header is verified
 * before any business logic runs. Any tampered or unsigned request → 400.
 *
 * PARSING: We deliberately avoid the Stripe SDK's EventDataObjectDeserializer
 * because in SDK v29.x it silently returns Optional.empty() when the Stripe
 * API version in the event payload doesn't exactly match the SDK's expected
 * version. Instead we parse the raw JSON with Jackson — more reliable and
 * completely SDK-version-independent.
 *
 * LOCAL TESTING: Run `stripe listen --forward-to localhost:8080/api/webhooks/stripe`
 * and paste the printed whsec_... value into stripe.webhook-secret in
 * application-dev.properties, then restart Spring Boot.
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookController {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    // ── Startup check ─────────────────────────────────────────────────────────

    @PostConstruct
    public void validateConfig() {
        if (webhookSecret.startsWith("whsec_REPLACE_ME")) {
            log.error("╔══════════════════════════════════════════════════════════╗");
            log.error("║  ⚠️  STRIPE WEBHOOK SECRET IS NOT CONFIGURED!            ║");
            log.error("║  Run: stripe listen --forward-to localhost:8080/api/webhooks/stripe");
            log.error("║  Then paste the whsec_... into application-dev.properties ║");
            log.error("╚══════════════════════════════════════════════════════════╝");
        } else {
            log.info("Stripe webhook handler ready — secret prefix: {}...",
                    webhookSecret.substring(0, Math.min(12, webhookSecret.length())));
        }
    }

    // ── Main handler ──────────────────────────────────────────────────────────

    @PostMapping("/stripe")
    public ResponseEntity<String> handleStripeEvent(
            HttpServletRequest request,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        // ── 1. Read raw payload from the input stream ────────────────────────
        // Must happen before any parsing — Stripe's signature covers the exact bytes
        String payload;
        try {
            payload = new BufferedReader(
                    new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            log.error("Failed to read Stripe webhook body: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Could not read payload");
        }

        // ── 2. Verify HMAC signature ─────────────────────────────────────────
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe signature verification FAILED — check webhook-secret: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        log.info("Stripe event received: {} [{}]", event.getType(), event.getId());

        // ── 3. Parse data.object from raw JSON (SDK-version-independent) ─────
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode dataObject = root.path("data").path("object");

            switch (event.getType()) {
                case "checkout.session.completed" -> handleCheckoutCompleted(dataObject);
                case "customer.subscription.deleted" -> handleSubscriptionDeleted(dataObject);
                case "invoice.payment_failed" -> handlePaymentFailed(dataObject);
                default -> log.debug("Unhandled Stripe event type: {}", event.getType());
            }

        } catch (Exception e) {
            log.error("Error processing Stripe event {} [{}]: {}",
                    event.getType(), event.getId(), e.getMessage(), e);
            // Return 500 so Stripe CLI / dashboard retries the event
            return ResponseEntity.internalServerError().body("Handler error — will retry");
        }

        return ResponseEntity.ok("received");
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    private void handleCheckoutCompleted(JsonNode session) {
        log.info("checkout.session.completed — raw session: mode={}, clientReferenceId={}, metadata={}",
                session.path("mode").asText(),
                session.path("client_reference_id").asText("(null)"),
                session.path("metadata"));

        // We set clientReferenceId = userId when creating the session
        String userId = session.path("client_reference_id").asText(null);
        if (userId == null || userId.isBlank()) {
            // Fallback to metadata (belt-and-suspenders)
            userId = session.path("metadata").path("userId").asText(null);
        }

        if (userId == null || userId.isBlank()) {
            log.error("checkout.session.completed: cannot determine userId — skipping");
            return;
        }

        String type = session.path("metadata").path("type").asText("subscription");
        log.info("Processing checkout for userId={}, type={}", userId, type);

        if ("credits".equals(type)) {
            int qty = Integer.parseInt(session.path("metadata").path("quantity").asText("1"));
            userRepository.addSummaryCredits(userId, qty);
            log.info("✅ Added {} credit(s) to user {}", qty, userId);

        } else {
            // Pro subscription checkout completed
            String customerId   = session.path("customer").asText(null);
            String subscriptionId = session.path("subscription").asText(null);

            userRepository.updatePlanAndStatus(
                    userId, User.PlanTier.PRO, User.SubscriptionStatus.ACTIVE);
            log.info("✅ User {} upgraded to PRO", userId);

            if (customerId != null && subscriptionId != null) {
                userRepository.updateStripeIds(userId, customerId, subscriptionId);
                log.info("   Stripe IDs saved — customer={}, subscription={}", customerId, subscriptionId);
            } else {
                log.warn("   Missing Stripe IDs: customer={}, subscription={}", customerId, subscriptionId);
            }
        }
    }

    private void handleSubscriptionDeleted(JsonNode subscription) {
        String customerId = subscription.path("customer").asText(null);
        log.info("customer.subscription.deleted — customerId={}", customerId);
        if (customerId == null) return;

        userRepository.findByStripeCustomerId(customerId).ifPresentOrElse(
                user -> {
                    userRepository.updatePlanAndStatus(
                            user.getId(), User.PlanTier.FREE, User.SubscriptionStatus.CANCELED);
                    log.info("✅ User {} downgraded to FREE (subscription period ended)", user.getId());
                },
                () -> log.warn("No user found for Stripe customer {}", customerId)
        );
    }

    private void handlePaymentFailed(JsonNode invoice) {
        String customerId = invoice.path("customer").asText(null);
        log.warn("invoice.payment_failed — customerId={}", customerId);
        if (customerId == null) return;

        userRepository.findByStripeCustomerId(customerId).ifPresentOrElse(
                user -> {
                    userRepository.updatePlanAndStatus(
                            user.getId(), User.PlanTier.PRO, User.SubscriptionStatus.CANCELED);
                    log.warn("⚠️  User {} subscription suspended due to payment failure", user.getId());
                },
                () -> log.warn("No user found for Stripe customer {}", customerId)
        );
    }
}
