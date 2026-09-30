import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';

import { Meeting } from '../../models/meeting.model';

/**
 * Renders a user's past meetings as clickable cards
 * (mirrors the original Next.js `meeting-list`).
 */
@Component({
  selector: 'app-meeting-list',
  imports: [RouterLink, DatePipe],
  template: `
    <div class="meeting-list">
      @if (meetings().length === 0) {
        <p class="muted">No meetings yet. Paste a transcript above to get started.</p>
      } @else {
        <h2>Past Meetings</h2>
        @for (meeting of meetings(); track meeting.id) {
          <a
            [routerLink]="['/dashboard/meetings', meeting.id]"
            class="card"
          >
            <div class="card-head">
              <h3>{{ meeting.title }}</h3>
              <small>{{ meeting.createdAt | date }}</small>
            </div>
            <p class="muted">{{ meeting.summary || 'No summary yet.' }}</p>
            <span class="card-detail-link">Click here for detailed summary &rarr;</span>
          </a>
        }
      }
    </div>
  `,
})
export class MeetingListComponent {
  readonly meetings = input<Meeting[]>([]);
}