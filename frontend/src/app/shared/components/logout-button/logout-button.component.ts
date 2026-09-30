import { Component, inject } from '@angular/core';

import { AuthService } from '../../../core/auth/auth.service';

/** Clears the JWT session and returns to the landing page. */
@Component({
  selector: 'app-logout-button',
  template: `
    <button type="button" (click)="logout()" class="btn btn-ghost">Log out</button>
  `,
})
export class LogoutButtonComponent {
  private readonly auth = inject(AuthService);

  logout(): void {
    this.auth.logout();
  }
}