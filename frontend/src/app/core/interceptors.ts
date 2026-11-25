import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** Calls that never need a token: the public "find blood" API and login/sign-up. */
const ANONYMOUS = ['/api/auth/login', '/api/auth/signup-status', '/api/auth/signup-admin'];
function isAnonymous(url: string): boolean {
  return url.startsWith('/api/public/') || ANONYMOUS.includes(url);
}

/** Adds the bearer token to every /api call except the anonymous ones. */
export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthService).token();
  if (token && req.url.startsWith('/api') && !isAnonymous(req.url)) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req);
};

/**
 * One place for API errors. The backend always answers with problem+json ({ title, detail, errors }),
 * so we can show the server's message as-is. 401 means the token is missing or expired -> back to login.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const snack = inject(MatSnackBar);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (isAnonymous(req.url)) {
        // the public page and the login/sign-up forms show their own messages
      } else if (err.status === 401) {
        // several calls can fail at once (bell + page); log out and tell the user only once
        if (auth.isLoggedIn()) {
          auth.logout();
          snack.open('Your session has expired. Please sign in again.', 'OK', { duration: 4000 });
        }
      } else if (err.status === 0) {
        snack.open('Cannot reach the server.', 'OK', { duration: 4000 });
      } else {
        const body = err.error ?? {};
        const fieldErrors = body.errors ? Object.entries(body.errors).map(([f, m]) => `${f}: ${m}`).join(', ') : '';
        snack.open(fieldErrors || body.detail || body.title || 'Request failed', 'OK', { duration: 5000 });
      }
      return throwError(() => err);
    }),
  );
};
