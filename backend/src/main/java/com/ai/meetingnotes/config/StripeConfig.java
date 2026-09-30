package com.ai.meetingnotes.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Initialises the Stripe Java SDK with the secret API key at startup.
 * All Stripe calls (Session.create, Subscription.retrieve, etc.) use the
 * global Stripe.apiKey set here — no per-request configuration needed.
 */
@Configuration
@Slf4j
public class StripeConfig {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
        log.info("Stripe SDK initialised (test mode: {})", secretKey.startsWith("sk_test_"));
    }
}
