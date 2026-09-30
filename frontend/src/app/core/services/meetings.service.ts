import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Meeting, MeetingPage, MeetingRequest } from '../../shared/models/meeting.model';

/**
 * Typed API client for meeting endpoints. The auth interceptor attaches the
 * bearer token automatically, so callers don't manage headers.
 */
@Injectable({ providedIn: 'root' })
export class MeetingsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/meetings`;

  create(request: MeetingRequest): Observable<Meeting> {
    return this.http.post<Meeting>(this.base, request);
  }

  listPaged(): Observable<MeetingPage> {
    return this.http.get<MeetingPage>(this.base);
  }

  listAll(): Observable<Meeting[]> {
    return this.http.get<Meeting[]>(`${this.base}/all`);
  }

  get(id: string): Observable<Meeting> {
    return this.http.get<Meeting>(`${this.base}/${id}`);
  }
}