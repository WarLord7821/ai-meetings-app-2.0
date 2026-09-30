import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthResponse, User } from '../../shared/models/user.model';

const TOKEN_KEY = 'ai_meeting_notes_token';
const USER_KEY = 'ai_meeting_notes_user';

/**
 * Central JWT session store.
 *
 * The plan specifies stateless JWT auth with the token kept in localStorage.
 * Exposes the current signed-in user as an Angular signal so components react
 * to login/logout, plus typed methods that talk to the Spring Boot auth API.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  /** Reactive user signal — null when signed out. */
  readonly user = signal<User | null>(this.readStoredUser());

  get isAuthenticated(): boolean {
    return this.user() !== null;
  }

  /** Raw JWT for the auth interceptor. */
  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiBaseUrl}/api/auth/login`, {
        email,
        password,
      })
      .pipe(tap((res) => this.persistSession(res)));
  }

  register(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${environment.apiBaseUrl}/api/auth/register`, {
        email,
        password,
      })
      .pipe(tap((res) => this.persistSession(res)));
  }

  /** Refresh the cached profile from `GET /api/users/me`. */
  me(): Observable<User> {
    return this.http
      .get<User>(`${environment.apiBaseUrl}/api/users/me`)
      .pipe(tap((user) => this.setStoredUser(user)));
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.user.set(null);
    this.router.navigateByUrl('/');
  }

  // ── internals ──────────────────────────────────────────────────────

  private persistSession(res: AuthResponse): void {
    localStorage.setItem(TOKEN_KEY, res.token);
    this.setStoredUser(res.user);
  }

  private setStoredUser(user: User): void {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    this.user.set(user);
  }

  private readStoredUser(): User | null {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as User;
    } catch {
      return null;
    }
  }
}