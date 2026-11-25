import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule,
    MatProgressBarModule],
  template: `
    <div class="wrap">
      <mat-card class="box">
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        <div class="brand"><mat-icon>bloodtype</mat-icon> BloodNet</div>
        <p class="muted">Blood bank network: stock, requests and transfers</p>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-form-field class="full">
            <mat-label>Email</mat-label>
            <input matInput formControlName="email" type="email" autocomplete="username" />
          </mat-form-field>
          <mat-form-field class="full">
            <mat-label>Password</mat-label>
            <input matInput formControlName="password" type="password" autocomplete="current-password" />
          </mat-form-field>
          @if (error()) { <div class="error">{{ error() }}</div> }
          <button mat-flat-button color="primary" class="full" [disabled]="form.invalid || loading()">Sign in</button>
        </form>
        <p class="signup">Looking for blood? <a routerLink="/find">See availability near you</a>, no login needed.</p>
        @if (signupEnabled()) {
          <p class="signup">New administrator? <a routerLink="/signup">Create admin account</a></p>
        }
      </mat-card>
    </div>
  `,
  styles: [`
    .wrap { min-height: 100vh; display: grid; place-items: center; background: var(--bn-bg); padding: 16px; }
    .box { width: 100%; max-width: 380px; padding: 24px; border: 1px solid var(--bn-border); box-shadow: 0 2px 12px rgba(60, 40, 40, .06); }
    .brand { display: flex; align-items: center; gap: 8px; font-size: 24px; font-weight: 600; color: var(--bn-red); }
    .full { width: 100%; }
    form { display: flex; flex-direction: column; gap: 12px; margin-top: 16px; }
    .error { color: var(--bn-red); font-size: 13px; }
    .signup { margin: 16px 0 0; font-size: 13px; text-align: center; }
  `],
})
export class LoginComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);

  readonly loading = signal(false);
  readonly error = signal('');
  readonly signupEnabled = signal(false);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  constructor() {
    if (this.auth.isLoggedIn()) {
      this.router.navigateByUrl(this.auth.homeUrl());
    }
    // only show the sign-up link when the server has it switched on
    this.auth.signupStatus().subscribe({ next: s => this.signupEnabled.set(s.adminSignupEnabled), error: () => {} });
  }

  submit(): void {
    const { email, password } = this.form.getRawValue();
    this.loading.set(true);
    this.error.set('');
    this.auth.login(email, password).subscribe({
      next: () => this.router.navigateByUrl(this.auth.homeUrl()),
      error: (e: { status?: number }) => {
        this.loading.set(false);
        this.error.set(e.status === 401 || e.status === 400 ? 'Wrong email or password.'
          : e.status === 0 ? 'Cannot reach the server. Please try again.'
          : e.status === 429 ? 'Too many attempts. Please wait a minute and try again.'
          : 'Sign-in failed. Please try again.');
      },
    });
  }
}
