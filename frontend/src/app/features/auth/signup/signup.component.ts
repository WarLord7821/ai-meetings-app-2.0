import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../../core/auth/auth.service';

@Component({
  selector: 'app-signup',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="auth-page">
      <div class="auth-card">
        <h2>Create your account</h2>
        <p class="auth-sub">Start taking AI meeting notes today</p>

        @if (error()) {
          <div class="alert">{{ error() }}</div>
        }

        <form [formGroup]="form" (ngSubmit)="onSubmit()">
          <label for="email">Email address</label>
          <input
            id="email"
            type="email"
            formControlName="email"
            autocomplete="email"
            required
            placeholder="you@example.com"
            class="field"
          />

          <label for="password">Password</label>
          <input
            id="password"
            type="password"
            formControlName="password"
            autocomplete="new-password"
            required
            placeholder="At least 8 characters"
            class="field"
          />

          <button type="submit" [disabled]="loading()" class="btn btn-primary btn-block">
            {{ loading() ? 'Creating account...' : 'Sign up' }}
          </button>
        </form>

        <p class="auth-sub">
          Already have an account?
          <a routerLink="/login" class="accent">Log in</a>
        </p>
      </div>
    </div>
  `,
})
export class SignupComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly loading = signal(false);
  protected readonly error = signal('');

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  onSubmit(): void {
    if (this.form.invalid || this.loading()) return;

    this.loading.set(true);
    this.error.set('');

    this.auth.register(this.form.value.email!, this.form.value.password!).subscribe({
      next: () => this.router.navigateByUrl('/dashboard'),
      error: (err) => {
        const message = typeof err?.error === 'string'
          ? err.error
          : err?.error?.message;
        this.error.set(message ?? 'Could not create your account. Please try again.');
        this.loading.set(false);
      },
      complete: () => this.loading.set(false),
    });
  }
}