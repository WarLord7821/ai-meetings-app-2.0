package com.ai.meetingnotes.controller;

import com.ai.meetingnotes.dto.BillingStatusResponse;
import com.ai.meetingnotes.entity.User;
import com.ai.meetingnotes.repository.UserRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Billing management endpoints.
 *
 * GET  /api/billing/status          — current plan, credits, usage
 * POST /api/billing/checkout/pro    — create Stripe Checkout Session (subscription)
 * POST /api/billing/checkout/credits — create Stripe Checkout Session (one-time payment)
 * POST /api/billing/cancel          — cancel active Pro subscription at period end
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private static final int FREE_SUMMARY_LIMIT = 3;

    private final UserRepository userRepository;

    @Value("${stripe.pro-price-id}")
    private String proPriceId;

    @Value("${stripe.credits-price-id}")
    private String creditsPriceId;

    @Value("${stripe.frontend-url}")
    private String frontendUrl;

    // ── Status ────────────────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).body("Unauthenticated");

        return ResponseEntity.ok(new BillingStatusResponse(
                user.getPlanTier().name(),
                user.getSubscriptionStatus().name(),
                user.getSummaryCredits(),
                user.getSummaryCount(),
                FREE_SUMMARY_LIMIT
        ));
    }

    // ── Stripe Checkout: Pro subscription ────────────────────────────────────

    /**
     * Creates a Stripe Checkout Session in subscription mode for the Pro plan.
     * Returns { checkoutUrl } — Angular redirects the browser there.
     * On success, Stripe fires a webhook that sets planTier=PRO in the DB.
     */
    @PostMapping("/checkout/pro")
    public ResponseEntity<?> checkoutPro(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).body("Unauthenticated");

        try {
            SessionCreateParams.Builder params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setClientReferenceId(user.getId())
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(proPriceId)
                            .setQuantity(1L)
                            .build())
                    .setSuccessUrl(frontendUrl + "/dashboard/billing?success=subscription")
                    .setCancelUrl(frontendUrl + "/dashboard/billing?canceled=true")
                    .putMetadata("userId", user.getId())
                    .putMetadata("type", "subscription");

            // Re-use existing Stripe customer if the user has already paid before
            if (user.getStripeCustomerId() != null) {
                params.setCustomer(user.getStripeCustomerId());
            } else {
                params.setCustomerEmail(user.getEmail());
            }

            Session session = Session.create(params.build());
            return ResponseEntity.ok(Map.of("checkoutUrl", session.getUrl()));

        } catch (StripeException e) {
            log.error("Stripe checkout/pro error for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.internalServerError()
                    .body("Failed to create checkout session. Please try again.");
        }
    }

    // ── Stripe Checkout: Credits (one-time payment) ───────────────────────────

    /**
     * Creates a Stripe Checkout Session in payment mode for 1 summary credit ($1).
     * Returns { checkoutUrl } — Angular redirects the browser there.
     * On success, Stripe fires a webhook that increments summaryCredits by 1.
     */
    @PostMapping("/checkout/credits")
    public ResponseEntity<?> checkoutCredits(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).body("Unauthenticated");

        try {
            SessionCreateParams.Builder params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setClientReferenceId(user.getId())
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(creditsPriceId)
                            .setQuantity(1L)
                            .build())
                    .setSuccessUrl(frontendUrl + "/dashboard/billing?success=credits")
                    .setCancelUrl(frontendUrl + "/dashboard/billing?canceled=true")
                    .putMetadata("userId", user.getId())
                    .putMetadata("type", "credits")
                    .putMetadata("quantity", "1");

            if (user.getStripeCustomerId() != null) {
                params.setCustomer(user.getStripeCustomerId());
            } else {
                params.setCustomerEmail(user.getEmail());
            }

            Session session = Session.create(params.build());
            return ResponseEntity.ok(Map.of("checkoutUrl", session.getUrl()));

        } catch (StripeException e) {
            log.error("Stripe checkout/credits error for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.internalServerError()
                    .body("Failed to create checkout session. Please try again.");
        }
    }

    // ── Cancel subscription ───────────────────────────────────────────────────

    /**
     * Cancels the active Pro subscription.
     * - If the user has a real Stripe subscription → cancel_at_period_end = true (graceful)
     * - If the user was upgraded via the old stub → direct DB update (backward compat)
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).body("Unauthenticated");

        if (user.getPlanTier() != User.PlanTier.PRO
                || user.getSubscriptionStatus() != User.SubscriptionStatus.ACTIVE) {
            return ResponseEntity.badRequest().body("No active Pro subscription to cancel.");
        }

        // Real Stripe subscription — cancel at period end (user keeps Pro until billing cycle ends)
        if (user.getStripeSubscriptionId() != null) {
            try {
                Subscription subscription = Subscription.retrieve(user.getStripeSubscriptionId());
                subscription.update(SubscriptionUpdateParams.builder()
                        .setCancelAtPeriodEnd(true)
                        .build());
                // Mark as canceling — the webhook will set planTier=FREE when the period actually ends
                userRepository.updatePlanAndStatus(
                        user.getId(), User.PlanTier.PRO, User.SubscriptionStatus.CANCELED);
                return ResponseEntity.ok(
                        "Subscription will cancel at the end of the billing period. " +
                        "You keep Pro access until then.");
            } catch (StripeException e) {
                log.error("Stripe cancel error for user {}: {}", user.getId(), e.getMessage());
                return ResponseEntity.internalServerError()
                        .body("Failed to cancel subscription. Please try again.");
            }
        }

        // Stub-upgraded account — direct DB fallback
        userRepository.updatePlanAndStatus(
                user.getId(), User.PlanTier.FREE, User.SubscriptionStatus.CANCELED);
        return ResponseEntity.ok("Subscription cancelled. Free plan rules now apply.");
    }
}
