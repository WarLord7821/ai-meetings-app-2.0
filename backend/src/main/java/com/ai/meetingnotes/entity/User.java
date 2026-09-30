package com.ai.meetingnotes.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @Column(length = 36)
    private String id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "plan_tier", nullable = false)
    private PlanTier planTier = PlanTier.FREE;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "subscription_status", nullable = false)
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.FREE;

    /** Lifetime summary generation count. Never resets. FREE users are blocked at >= 3. */
    @Builder.Default
    @Column(name = "summary_count", nullable = false)
    private int summaryCount = 0;

    /** Pay-per-summary credits. Each credit allows one summary generation. Consumed before free limit check. */
    @Builder.Default
    @Column(name = "summary_credits", nullable = false)
    private int summaryCredits = 0;

    /** Stripe customer ID — set on first successful payment. Used for webhook lookups and subscription management. */
    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    /** Stripe subscription ID — set when user upgrades to Pro. Used to cancel the subscription via Stripe API. */
    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Meeting> meetings;

    public enum PlanTier {
        FREE, PRO
    }

    public enum SubscriptionStatus {
        FREE, ACTIVE, CANCELED
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}