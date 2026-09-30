import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ChatAnswer, ChatRequest } from '../../shared/models/chat.model';

/**
 * Typed API client for the RAG chatbot endpoint. The auth interceptor attaches
 * the bearer token automatically, so callers don't manage headers.
 */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/chat`;

  ask(request: ChatRequest): Observable<ChatAnswer> {
    return this.http.post<ChatAnswer>(this.base, request);
  }
}
