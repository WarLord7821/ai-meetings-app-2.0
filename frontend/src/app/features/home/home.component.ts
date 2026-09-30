import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Public marketing / landing page.
 * Replicates the original Next.js home page with the indigo/zinc palette.
 * (Stripe/Pro billing was removed per the plan — the product is free.)
 */
@Component({
  selector: 'app-home',
  imports: [RouterLink],
  template: `
    <header class="nav">
      <a routerLink="/" class="brand">AI Meeting Notes</a>
      <nav class="nav-links">
        <a routerLink="/login" class="link">Log in</a>
        <a routerLink="/signup" class="btn btn-primary">Sign up</a>
      </nav>
    </header>

    <main>
      <section class="hero">
        <h1>
          Turn meeting transcripts into
          <span class="accent">instant summaries</span> and action items
        </h1>
        <p class="hero-sub">
          Stop spending hours re-reading notes and recordings. Paste your
          transcript and get a concise summary with clear next steps — in
          seconds, not hours.
        </p>
        <a routerLink="/signup" class="btn btn-primary btn-lg">Get Started Free</a>
      </section>

      <section class="features">
        <h2>Why use AI Meeting Notes?</h2>
        <div class="feature-grid">
          <div class="feature-card">
            <span class="feature-icon">⚡</span>
            <h3>Instant summaries</h3>
            <p>Paste a transcript and get a clear, structured summary in seconds.</p>
          </div>
          <div class="feature-card">
            <span class="feature-icon">✅</span>
            <h3>Action items extracted</h3>
            <p>Automatically pull out decisions, owners, and next steps.</p>
          </div>
          <div class="feature-card">
            <span class="feature-icon">🕒</span>
            <h3>Hours saved every week</h3>
            <p>Stop re-reading long recordings. Get the highlights and move on.</p>
          </div>
          <div class="feature-card">
            <span class="feature-icon">🔒</span>
            <h3>Private by default</h3>
            <p>Secure JWT authentication keeps your meeting data safe.</p>
          </div>
        </div>
      </section>

      <section class="pricing">
        <h2>Simple, honest pricing</h2>
        <div class="pricing-card">
          <span class="badge">Most Popular</span>
          <h3>Free</h3>
          <p class="price"><strong>$0</strong> <span>/month</span></p>
          <ul>
            <li>3 AI summaries per day</li>
            <li>Action item extraction</li>
            <li>Meeting history</li>
            <li>No credit card required</li>
          </ul>
          <a routerLink="/signup" class="btn btn-primary btn-block">Sign up free</a>
        </div>
      </section>
    </main>

    <footer class="footer">
      <p>© {{ currentYear }} AI Meeting Notes. All rights reserved.</p>
      <nav class="footer-links">
        <a href="#">Privacy</a>
        <a href="#">Terms</a>
      </nav>
    </footer>
  `,
})
export class HomeComponent {
  currentYear = new Date().getFullYear();
}