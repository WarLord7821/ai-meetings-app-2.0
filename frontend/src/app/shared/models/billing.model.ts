/** Mirrors backend `BillingStatusResponse`. */
export interface BillingStatus {
  planTier: 'FREE' | 'PRO';
  subscriptionStatus: 'FREE' | 'ACTIVE' | 'CANCELED';
  summaryCredits: number;
  summaryCount: number;
  freeLimit: number;
}
