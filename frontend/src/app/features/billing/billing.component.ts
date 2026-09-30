import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink, ActivatedRoute } from '@angular/router';
import { TitleCasePipe } from '@angular/common';

import { AuthService } from '../../core/auth/auth.service';
import { BillingService } from '../../core/services/billing.service';
import { BillingStatus } from '../../shared/models/billing.model';

/**
 * Billing management page — route: /dashboard/billing
 *
 * Handles:
 *  - Showing the current plan, credit balance, and usage.
 *  - Stripe Checkout redirect for Pro upgrade and credit purchase.
 *  - Reading ?success=... / ?canceled=true query params after returning from Stripe.
 *  - Cancelling Pro subscription at period end.
 */
@Component({
  selector: 'app-billing',
  imports: [RouterLink, TitleCasePipe],
  template: `
    <div class="billing-page">
      <header class="app-header">
        <div>
          <h1>AI Meeting Notes</h1>
          <p class="muted">Billing &amp; Plan</p>
        </div>
        <div class="header-actions">
          <a routerLink="/dashboard" class="btn btn-secondary">← Back to Dashboard</a>
        </div>
      </header>

      <main class="billing-main">

        <!-- ── Return banners from Stripe ──────────────────────────────── -->
        @if (returnSuccess()) {
          <div class="alert alert-success">
            @if (returnSuccess() === 'subscription') {
              🎉 <strong>You're now on Pro!</strong> Unlimited summaries are unlocked.
              Your plan will refresh below shortly.
            } @else if (returnSuccess() === 'credits') {
              ✅ <strong>1 summary credit added!</strong> You're good to go.
            } @else {
              ✅ Payment successful!
            }
          </div>
        }

        @if (returnCanceled()) {
          <div class="alert alert-warn">
            ⚠️ Payment was cancelled — no charge was made.
          </div>
        }

        @if (error()) {
          <div class="alert alert-error">{{ error() }}</div>
        }

        <!-- ── Plan & Status Card ────────────────────────────────────────── -->
        <section class="billing-card">
          <h2>Current Plan</h2>

          @if (status()) {
            <div class="plan-row">
              <div class="plan-badge" [class.badge-pro]="status()!.planTier === 'PRO'"
                                      [class.badge-free]="status()!.planTier === 'FREE'">
                {{ status()!.planTier === 'PRO' ? '⚡ Pro' : '🔓 Free' }}
              </div>
              <div class="plan-status muted">
                Status: {{ status()!.subscriptionStatus | titlecase }}
              </div>
            </div>

            <div class="usage-row">
              <div class="usage-item">
                <span class="usage-label">Summaries used</span>
                <span class="usage-value">{{ status()!.summaryCount }}</span>
                @if (status()!.planTier === 'FREE') {
                  <span class="usage-cap"> / {{ status()!.freeLimit }} lifetime</span>
                }
              </div>
              <div class="usage-item">
                <span class="usage-label">Summary credits</span>
                <span class="usage-value" [class.credits-positive]="status()!.summaryCredits > 0">
                  {{ status()!.summaryCredits }}
                </span>
                <span class="usage-cap"> remaining</span>
              </div>
            </div>
          } @else {
            <p class="muted">Loading billing status…</p>
          }
        </section>

        <!-- ── Upgrade to Pro ────────────────────────────────────────────── -->
        @if (status()?.planTier === 'FREE' || status()?.subscriptionStatus === 'CANCELED') {
          <section class="billing-card billing-card-highlight">
            <h2>⚡ Upgrade to Pro</h2>
            <p class="muted">Unlimited AI summaries — no daily limits, no credits to track.</p>
            <div class="price-row">
              <span class="price">$49</span>
              <span class="price-period">/month</span>
            </div>
            <ul class="feature-list">
              <li>✓ Unlimited AI summaries</li>
              <li>✓ Priority processing</li>
              <li>✓ Full meeting history</li>
              <li>✓ Action item extraction</li>
            </ul>
            <button
              id="upgrade-btn"
              class="btn btn-primary"
              [disabled]="actionLoading()"
              (click)="onUpgrade()">
              @if (actionLoading()) {
                <span class="spinner"></span> Redirecting to Stripe…
              } @else {
                Upgrade to Pro — $49/month
              }
            </button>
            <p class="muted small">Secured by Stripe. Cancel anytime from this page.</p>
          </section>
        }

        <!-- ── Buy Summary Credits ───────────────────────────────────────── -->
        <section class="billing-card">
          <h2>🪙 Buy Summary Credits</h2>
          <p class="muted">
            Need just one more summary? Credits never expire and are consumed before
            your free-plan quota. Pro subscribers don't use credits while their subscription is active.
          </p>
          <div class="price-row">
            <span class="price">$1</span>
            <span class="price-period">/ credit</span>
          </div>
          <button
            id="buy-credit-btn"
            class="btn btn-secondary"
            [disabled]="actionLoading()"
            (click)="onBuyCredit()">
            @if (actionLoading()) {
              <span class="spinner"></span> Redirecting to Stripe…
            } @else {
              Buy 1 Summary Credit — $1
            }
          </button>
          <p class="muted small">Secured by Stripe. One-time charge, no subscription.</p>
        </section>

        <!-- ── Cancel Subscription ───────────────────────────────────────── -->
        @if (status()?.planTier === 'PRO' && status()?.subscriptionStatus === 'ACTIVE') {
          <section class="billing-card billing-card-danger">
            <h2>Cancel Subscription</h2>
            <p class="muted">
              Your Pro access continues until the end of the current billing period.
              After that, free plan limits apply. Unused credits are always preserved.
            </p>
            <button
              id="cancel-btn"
              class="btn btn-danger"
              [disabled]="actionLoading()"
              (click)="onCancel()">
              {{ actionLoading() ? 'Processing…' : 'Cancel Pro Subscription' }}
            </button>
          </section>
        }

      </main>
    </div>
  `,
  styles: [`
    .billing-page { min-height: 100vh; background: var(--bg, #f8fafc); }

    .billing-main {
      max-width: 680px;
      margin: 0 auto;
      padding: 2rem 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }

    .billing-card {
      background: #fff;
      border: 1px solid #e2e8f0;
      border-radius: 1rem;
      padding: 1.75rem;
    }
    .billing-card h2 { margin: 0 0 1rem; font-size: 1.1rem; }
    .billing-card-highlight {
      border: 2px solid #6366f1;
      background: linear-gradient(135deg, #eef2ff 0%, #fff 60%);
    }
    .billing-card-danger { border-color: #fca5a5; background: #fff5f5; }

    /* Plan row */
    .plan-row { display: flex; align-items: center; gap: 1rem; margin-bottom: 1.25rem; }
    .plan-badge {
      display: inline-flex; align-items: center;
      padding: 0.35rem 0.9rem;
      border-radius: 999px;
      font-weight: 700; font-size: 0.9rem;
    }
    .badge-pro { background: #6366f1; color: #fff; }
    .badge-free { background: #e2e8f0; color: #475569; }

    /* Usage grid */
    .usage-row { display: flex; gap: 2rem; flex-wrap: wrap; }
    .usage-item { display: flex; align-items: baseline; gap: 0.35rem; }
    .usage-label { font-size: 0.85rem; color: #64748b; }
    .usage-value { font-size: 1.4rem; font-weight: 700; color: #1e293b; }
    .usage-cap { font-size: 0.8rem; color: #94a3b8; }
    .credits-positive { color: #059669; }

    /* Pricing */
    .price-row { display: flex; align-items: baseline; gap: 0.25rem; margin: 0.75rem 0 1rem; }
    .price { font-size: 2.5rem; font-weight: 800; color: #1e293b; }
    .price-period { font-size: 1rem; color: #64748b; }

    /* Feature list */
    .feature-list { list-style: none; padding: 0; margin: 0 0 1.25rem; display: flex; flex-direction: column; gap: 0.4rem; }
    .feature-list li { font-size: 0.9rem; color: #334155; }

    /* Alerts */
    .alert { padding: 0.85rem 1.25rem; border-radius: 0.5rem; font-size: 0.9rem; }
    .alert-error { background: #fef2f2; border: 1px solid #fca5a5; color: #991b1b; }
    .alert-success { background: #f0fdf4; border: 1px solid #86efac; color: #166534; }
    .alert-warn { background: #fffbeb; border: 1px solid #fcd34d; color: #92400e; }

    /* Buttons */
    .btn {
      display: inline-flex; align-items: center; justify-content: center; gap: 0.5rem;
      padding: 0.65rem 1.4rem;
      border-radius: 0.5rem;
      font-size: 0.9rem; font-weight: 600;
      cursor: pointer; border: none;
      transition: opacity 0.15s, transform 0.1s;
    }
    .btn:disabled { opacity: 0.6; cursor: not-allowed; }
    .btn:not(:disabled):hover { opacity: 0.9; transform: translateY(-1px); }
    .btn-primary { background: #6366f1; color: #fff; }
    .btn-secondary { background: #f1f5f9; color: #334155; border: 1px solid #e2e8f0; }
    .btn-danger { background: #ef4444; color: #fff; }

    /* Loading spinner */
    .spinner {
      display: inline-block;
      width: 14px; height: 14px;
      border: 2px solid rgba(255,255,255,0.4);
      border-top-color: #fff;
      border-radius: 50%;
      animation: spin 0.7s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }

    .muted { color: #64748b; font-size: 0.875rem; }
    .small { font-size: 0.78rem; margin-top: 0.6rem; }
  `],
})
export class BillingComponent implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly billing = inject(BillingService);
  private readonly route = inject(ActivatedRoute);

  protected readonly status = signal<BillingStatus | null>(null);
  protected readonly error = signal('');
  protected readonly returnSuccess = signal<string | null>(null);
  protected readonly returnCanceled = signal(false);
  protected readonly actionLoading = signal(false);

  ngOnInit(): void {
    this.handleReturnParams();
    this.loadStatus();
  }

  /** Reads ?success=... / ?canceled=true set by Stripe after checkout redirect. */
  private handleReturnParams(): void {
    const successParam = this.route.snapshot.queryParamMap.get('success');
    const canceledParam = this.route.snapshot.queryParamMap.get('canceled');
    if (successParam) {
      this.returnSuccess.set(successParam);
      // After a successful payment, re-fetch the user profile so dashboard badges update
      this.auth.me().subscribe();
    }
    if (canceledParam === 'true') {
      this.returnCanceled.set(true);
    }
  }

  private loadStatus(): void {
    this.billing.getStatus().subscribe({
      next: (s) => this.status.set(s),
      error: () => this.error.set('Failed to load billing status. Please refresh.'),
    });
  }

  /** Refresh billing widget + auth signal after an action. */
  private refresh(): void {
    this.loadStatus();
    this.auth.me().subscribe();
  }

  // ── Stripe Checkout redirects ─────────────────────────────────────────────

  onUpgrade(): void {
    this.actionLoading.set(true);
    this.error.set('');

    this.billing.checkoutPro().subscribe({
      next: ({ checkoutUrl }) => {
        // Redirect to Stripe's hosted checkout page
        window.location.href = checkoutUrl;
      },
      error: (err) => {
        this.error.set(err?.error ?? 'Failed to start checkout. Please try again.');
        this.actionLoading.set(false);
      },
      // Do NOT reset loading on complete — the page is navigating away
    });
  }

  onBuyCredit(): void {
    this.actionLoading.set(true);
    this.error.set('');

    this.billing.checkoutCredits().subscribe({
      next: ({ checkoutUrl }) => {
        window.location.href = checkoutUrl;
      },
      error: (err) => {
        this.error.set(err?.error ?? 'Failed to start checkout. Please try again.');
        this.actionLoading.set(false);
      },
    });
  }

  // ── Cancel ───────────────────────────────────────────────────────────────

  onCancel(): void {
    if (!confirm('Are you sure you want to cancel your Pro subscription?\n\nYou keep Pro access until the end of the current billing period.')) {
      return;
    }

    this.actionLoading.set(true);
    this.error.set('');

    this.billing.cancel().subscribe({
      next: (msg) => {
        this.error.set('');
        this.returnSuccess.set('canceled_acknowledged');
        this.refresh();
      },
      error: (err) => this.error.set(err?.error ?? 'Cancellation failed. Please try again.'),
      complete: () => this.actionLoading.set(false),
    });
  }
}
