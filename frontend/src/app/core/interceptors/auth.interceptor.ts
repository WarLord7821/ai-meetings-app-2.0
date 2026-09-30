import { inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';

/**
 * Functional HTTP interceptor (registered via `withInterceptors`).
 * Attaches `Authorization: Bearer <jwt>` to every outgoing request when a
 * token exists in localStorage. On a 401 the session is cleared and the user
 * is redirected to /login.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.getToken();

  const outgoing = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(outgoing).pipe(
    catchError((err) => {
      if (err.status === 401) {
        auth.logout(); // clears localStorage + navigates to /
      }
      return throwError(() => err);
    }),
  );
};