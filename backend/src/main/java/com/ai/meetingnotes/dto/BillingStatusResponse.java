package com.ai.meetingnotes.dto;

/**
 * Response body for GET /api/billing/status.
 * Carries everything the frontend billing page needs in one call.
 */
public record BillingStatusResponse(
        String planTier,
        String subscriptionStatus,
        int summaryCredits,
        int summaryCount,
        int freeLimit
) {}
