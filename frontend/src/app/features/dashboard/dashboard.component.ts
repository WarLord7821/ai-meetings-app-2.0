import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { MeetingsService } from '../../core/services/meetings.service';
import { Meeting } from '../../shared/models/meeting.model';
import { MeetingFormComponent } from '../../shared/components/meeting-form/meeting-form.component';
import { MeetingListComponent } from '../../shared/components/meeting-list/meeting-list.component';
import { ChatPanelComponent } from '../../shared/components/chat-panel/chat-panel.component';
import { LogoutButtonComponent } from '../../shared/components/logout-button/logout-button.component';

/**
 * Authenticated dashboard: AI summary form, RAG chatbot, and meeting history.
 */
@Component({
  selector: 'app-dashboard',
  imports: [MeetingFormComponent, MeetingListComponent, ChatPanelComponent, LogoutButtonComponent, RouterLink],
  template: `
    <header class="app-header">
      <div class="app-header-brand">
        <h1>AI Meeting Notes</h1>
        <div class="plan-row">
          <span class="plan-email">{{ auth.user()?.email }}</span>
          @if (auth.user()?.planTier === 'PRO') {
            <span class="plan-badge plan-badge-pro">⚡ Pro</span>
          } @else {
            <span class="plan-badge plan-badge-free">Free Plan</span>
          }
          @if ((auth.user()?.summaryCredits ?? 0) > 0) {
            <span class="plan-badge plan-badge-credits">
              🪙 {{ auth.user()!.summaryCredits }} credit{{ auth.user()!.summaryCredits === 1 ? '' : 's' }}
            </span>
          }
        </div>
      </div>
      <div class="header-actions">
        <a routerLink="/dashboard/billing" class="nav-link">Billing</a>
        <app-logout-button />
      </div>
    </header>

    <main class="dashboard-main">
      <app-meeting-form (created)="loadMeetings()" />
      <app-chat-panel />
      <app-meeting-list [meetings]="meetings()" />
    </main>
  `,
  styles: [`
    .app-header-brand {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    .app-header-brand h1 { line-height: 1.2; }

    .plan-row {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
    }
    .plan-email { color: var(--muted); font-size: 0.875rem; }

    /*
     * NOTE: intentionally named "plan-badge*", not "badge" — the global
     * stylesheet defines a ".badge" class for the pricing page's "Most
     * Popular" ribbon with position:absolute (see styles.css .pricing-card
     * .badge). Angular's emulated view encapsulation scopes color/padding
     * here but does NOT stop that global rule's *other* properties from
     * also applying to any element sharing the plain ".badge" class name —
     * so reusing "badge" here previously pinned this badge to the top of
     * the page instead of inline in the header.
     */
    .plan-badge {
      display: inline-flex;
      align-items: center;
      position: static;
      padding: 0.15rem 0.6rem;
      border-radius: 999px;
      font-size: 0.75rem;
      font-weight: 600;
      white-space: nowrap;
    }
    .plan-badge-pro { background: var(--indigo); color: #fff; }
    .plan-badge-free { background: var(--bg); color: var(--muted); border: 1px solid var(--border); }
    .plan-badge-credits { background: #d1fae5; color: #065f46; }

    .nav-link {
      font-size: 0.875rem;
      color: var(--indigo);
      text-decoration: none;
      font-weight: 500;
    }
    .nav-link:hover { text-decoration: underline; }
  `],
})
export class DashboardComponent implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly http = inject(MeetingsService);

  protected meetings = signal<Meeting[]>([]);

  ngOnInit(): void {
    this.loadMeetings();
  }

  loadMeetings(): void {
    this.http.listAll().subscribe({
      next: (meetings) => this.meetings.set(meetings),
      error: (err) => console.error('Failed to load meetings', err),
    });
  }
}
