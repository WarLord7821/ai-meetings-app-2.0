/** Plan tier, mirrors backend `User.PlanTier`. */
export type PlanTier = 'FREE' | 'PRO';

/** Subscription status — mirrors backend `User.SubscriptionStatus`. */
export type SubscriptionStatus = 'FREE' | 'ACTIVE' | 'CANCELED';

/** Mirrors the backend `AuthResponse.UserDto`. */
export interface User {
  id: string;
  email: string;
  planTier: PlanTier;
  subscriptionStatus: SubscriptionStatus;
  summaryCredits: number;
}

/** Mirrors the backend auth endpoints' response body. */
export interface AuthResponse {
  token: string;
  type: string;
  user: User;
}