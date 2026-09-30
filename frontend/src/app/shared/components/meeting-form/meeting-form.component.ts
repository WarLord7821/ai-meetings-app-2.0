import { Component, inject, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { MeetingsService } from '../../../core/services/meetings.service';

/**
 * Form for pasting a meeting transcript and generating an AI summary.
 * Emits `created` so the hosting dashboard can refresh its list.
 */
@Component({
  selector: 'app-meeting-form',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="meeting-form">
      <h2>New Meeting Summary</h2>

      @if (limitReached()) {
        <div class="alert alert-limit">
          <strong>⚡ Summary limit reached.</strong>
          <p>You've used all your available summaries.</p>
          <div class="limit-actions">
            <a routerLink="/dashboard/billing" class="btn btn-primary btn-sm">Upgrade to Pro — $49/month</a>
            <a routerLink="/dashboard/billing" class="btn btn-secondary btn-sm">Buy 1 Credit — $1</a>
          </div>
        </div>
      } @else if (error()) {
        <div class="alert">{{ error() }}</div>
      }

      <form [formGroup]="form" (ngSubmit)="onSubmit()">
        <input
          type="text"
          formControlName="title"
          placeholder="Meeting title (optional)"
          class="field"
        />

        <textarea
          formControlName="transcript"
          rows="8"
          required
          placeholder="Paste your meeting transcript here..."
          class="field"
        ></textarea>

        <button type="submit" [disabled]="loading()" class="btn btn-primary">
          {{ loading() ? 'Generating summary...' : 'Generate Summary' }}
        </button>
      </form>
    </div>
  `,
  styles: [`
    .alert-limit {
      background: #fffbeb;
      border: 1px solid #fcd34d;
      border-radius: 0.5rem;
      padding: 1rem 1.25rem;
      color: #92400e;
    }
    .alert-limit p { margin: 0.4rem 0 0.75rem; font-size: 0.875rem; }
    .limit-actions { display: flex; gap: 0.75rem; flex-wrap: wrap; }
    .btn-sm { padding: 0.45rem 1rem; font-size: 0.8rem; }
  `],
})
export class MeetingFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly meetings = inject(MeetingsService);

  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly limitReached = signal(false);

  /** Emitted after a meeting is created so the dashboard can reload. */
  readonly created = output<void>();

  protected readonly form = this.fb.nonNullable.group({
    title: [''],
    transcript: ['', [Validators.required, Validators.minLength(20)]],
  });

  onSubmit(): void {
    if (this.form.invalid || this.loading()) return;

    this.loading.set(true);
    this.error.set('');
    this.limitReached.set(false);

    this.meetings
      .create({
        title: this.form.value.title ?? '',
        transcript: this.form.value.transcript!,
      })
      .subscribe({
        next: () => {
          this.form.reset();
          this.created.emit();
        },
        error: (err) => {
          const body = err?.error;
          // Backend returns { error: string, limitReached: boolean }
          if (body?.limitReached === true) {
            this.limitReached.set(true);
          } else {
            const message = typeof body === 'string' ? body : (body?.error ?? 'Something went wrong. Please try again.');
            this.error.set(message);
          }
          this.loading.set(false);
        },
        complete: () => this.loading.set(false),
      });
  }
}