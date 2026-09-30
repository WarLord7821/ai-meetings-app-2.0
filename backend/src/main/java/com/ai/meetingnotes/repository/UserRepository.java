package com.ai.meetingnotes.repository;

import com.ai.meetingnotes.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Atomically increments the lifetime summary counter for a user. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.summaryCount = u.summaryCount + 1 WHERE u.id = :id")
    void incrementSummaryCount(@Param("id") String id);

    /**
     * Atomically decrements summaryCredits by 1 only when credits > 0.
     * Returns the number of rows updated (1 = success, 0 = no credits left).
     * This conditional UPDATE is race-safe without requiring a separate SELECT.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.summaryCredits = u.summaryCredits - 1 WHERE u.id = :id AND u.summaryCredits > 0")
    int decrementSummaryCredits(@Param("id") String id);

    /** Sets planTier and subscriptionStatus — used by billing upgrade/cancel flows. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.planTier = :tier, u.subscriptionStatus = :status WHERE u.id = :id")
    void updatePlanAndStatus(
            @Param("id") String id,
            @Param("tier") User.PlanTier tier,
            @Param("status") User.SubscriptionStatus status);

    /** Adds N credits to a user's balance (used by pay-per-summary purchase flow). */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.summaryCredits = u.summaryCredits + :amount WHERE u.id = :id")
    void addSummaryCredits(@Param("id") String id, @Param("amount") int amount);

    /**
     * Looks up a user by their Stripe customer ID.
     * Used in webhook handlers where the event carries a customerId but not a userId.
     */
    Optional<User> findByStripeCustomerId(String stripeCustomerId);

    /** Persists the Stripe customer + subscription IDs after a successful Pro checkout. */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.stripeCustomerId = :customerId, u.stripeSubscriptionId = :subscriptionId WHERE u.id = :id")
    void updateStripeIds(
            @Param("id") String id,
            @Param("customerId") String customerId,
            @Param("subscriptionId") String subscriptionId);
}