import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { BillingStatus } from '../../shared/models/billing.model';

/**
 * Typed client for the Spring Boot /api/billing endpoints.
 * The JWT auth interceptor attaches the bearer token automatically.
 */
@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/billing`;

  /** Fetches current plan, status, credits balance, and usage counter. */
  getStatus(): Observable<BillingStatus> {
    return this.http.get<BillingStatus>(`${this.base}/status`);
  }

  /**
   * Creates a Stripe Checkout Session for the Pro subscription.
   * Returns { checkoutUrl } — caller should redirect window.location there.
   */
  checkoutPro(): Observable<{ checkoutUrl: string }> {
    return this.http.post<{ checkoutUrl: string }>(`${this.base}/checkout/pro`, null);
  }

  /**
   * Creates a Stripe Checkout Session for 1 summary credit ($1 one-time).
   * Returns { checkoutUrl } — caller should redirect window.location there.
   */
  checkoutCredits(): Observable<{ checkoutUrl: string }> {
    return this.http.post<{ checkoutUrl: string }>(`${this.base}/checkout/credits`, null);
  }

  /** Cancels the active Pro subscription at period end. */
  cancel(): Observable<string> {
    return this.http.post(`${this.base}/cancel`, null, { responseType: 'text' });
  }
}
