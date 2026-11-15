import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { LoginResponse, Role, User } from './models';

const TOKEN_KEY = 'bloodnet.token';
const USER_KEY = 'bloodnet.user';
const EXPIRES_KEY = 'bloodnet.expiresAt';

/** Where each role lands after login. */
export const HOME_BY_ROLE: Record<Role, string> = {
  ADMIN: '/dashboard',
  BANK_STAFF: '/dashboard',
  HOSPITAL: '/hospital',
};

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  // sessionStorage: the token is gone when the tab closes. Trade-off vs. an httpOnly cookie is in decisions.md.
  private readonly _user = signal<User | null>(this.restoreUser());

  readonly user = this._user.asReadonly();
  readonly isLoggedIn = computed(() => this._user() !== null);
  readonly role = computed(() => this._user()?.role ?? null);

  login(email: string, password: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/auth/login', { email, password }).pipe(
      tap(res => {
        sessionStorage.setItem(TOKEN_KEY, res.token);
        sessionStorage.setItem(EXPIRES_KEY, res.expiresAt);
        sessionStorage.setItem(USER_KEY, JSON.stringify(res.user));
        this._user.set(res.user);
      }),
    );
  }

  logout(redirect = true): void {
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(USER_KEY);
    sessionStorage.removeItem(EXPIRES_KEY);
    this._user.set(null);
    if (redirect) {
      this.router.navigate(['/login']);
    }
  }

  token(): string | null {
    const expires = sessionStorage.getItem(EXPIRES_KEY);
    if (expires && new Date(expires).getTime() <= Date.now()) {
      this.logout(false);
      return null;
    }
    return sessionStorage.getItem(TOKEN_KEY);
  }

  homeUrl(): string {
    const role = this.role();
    return role ? HOME_BY_ROLE[role] : '/login';
  }

  hasRole(...roles: Role[]): boolean {
    const r = this.role();
    return r !== null && roles.includes(r);
  }

  private restoreUser(): User | null {
    const raw = sessionStorage.getItem(USER_KEY);
    const expires = sessionStorage.getItem(EXPIRES_KEY);
    if (!raw || !expires || new Date(expires).getTime() <= Date.now()) {
      return null;
    }
    try {
      return JSON.parse(raw) as User;
    } catch {
      return null;
    }
  }
}
