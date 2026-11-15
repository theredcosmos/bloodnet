import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { Role } from './models';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isLoggedIn() && auth.token() ? true : inject(Router).createUrlTree(['/login']);
};

/** Site root: visitors see the public "find blood" page, signed-in users go to their home page. */
export const landingGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return inject(Router).createUrlTree([auth.isLoggedIn() && auth.token() ? auth.homeUrl() : '/find']);
};

/** Empty path -> the logged-in user's home page (staff: dashboard, hospital: portal). */
export const homeGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return inject(Router).createUrlTree([auth.homeUrl()]);
};

/**
 * Route data: { roles: ['ADMIN', 'BANK_STAFF'] }. Wrong role -> sent to their own home page.
 * This is only for UX; the real check is @PreAuthorize on the server.
 */
export const roleGuard: CanActivateFn = route => {
  const auth = inject(AuthService);
  const roles = (route.data['roles'] ?? []) as Role[];
  if (!auth.isLoggedIn()) {
    return inject(Router).createUrlTree(['/login']);
  }
  return roles.length === 0 || auth.hasRole(...roles) ? true : inject(Router).createUrlTree([auth.homeUrl()]);
};
