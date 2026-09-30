import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';

import { MeetingsService } from '../../core/services/meetings.service';
import { Meeting } from '../../shared/models/meeting.model';

/**
 * Read-only detail page for a single meeting.
 * Route: /dashboard/meetings/:id
 */
@Component({
  selector: 'app-meeting-detail',
  imports: [RouterLink, DatePipe],
  template: `
    <div class="detail-page">
      <a routerLink="/dashboard" class="back-link">&larr; Back to Dashboard</a>

      <h1>{{ meeting()?.title }}</h1>
      <p class="muted">{{ meeting()?.createdAt ? (meeting()!.createdAt | date: 'medium') : '' }}</p>

      <section class="section-card">
        <h2>Summary</h2>
        <p>{{ meeting()?.summary }}</p>
      </section>

      @if (meeting()?.objectives?.length) {
        <section class="section-card">
          <h2>Objectives</h2>
          <ul>
            @for (item of meeting()?.objectives; track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>
      }

      @if (meeting()?.keyPoints?.length) {
        <section class="section-card">
          <h2>Key Points</h2>
          <ul>
            @for (item of meeting()?.keyPoints; track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>
      }

      @if (meeting()?.decisions?.length) {
        <section class="section-card">
          <h2>Decisions</h2>
          <ul>
            @for (item of meeting()?.decisions; track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>
      }

      @if (meeting()?.outcomes?.length) {
        <section class="section-card">
          <h2>Outcomes</h2>
          <ul>
            @for (item of meeting()?.outcomes; track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>
      }

      @if (meeting()?.actionItems?.length) {
        <section class="section-card">
          <h2>Action Items</h2>
          <ul>
            @for (item of meeting()?.actionItems; track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>
      }

      @if (meeting()?.nextSteps?.length) {
        <section class="section-card">
          <h2>Next Steps</h2>
          <ul>
            @for (item of meeting()?.nextSteps; track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>
      }

      @if (meeting()?.pendingDiscussions?.length) {
        <section class="section-card">
          <h2>Pending Discussions</h2>
          <ul>
            @for (item of meeting()?.pendingDiscussions; track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>
      }

      <section class="section-card">
        <h2>Full Transcript</h2>
        <p class="whitespace">{{ meeting()?.rawTranscript }}</p>
      </section>

      @if (notFound()) {
        <div class="alert">Meeting not found.</div>
      }
    </div>
  `,
})
export class MeetingDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly http = inject(MeetingsService);

  protected readonly meeting = signal<Meeting | null>(null);
  protected readonly notFound = signal(false);

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) return;

    this.http.get(id).subscribe({
      next: (meeting) => this.meeting.set(meeting),
      error: () => this.notFound.set(true),
    });
  }
}