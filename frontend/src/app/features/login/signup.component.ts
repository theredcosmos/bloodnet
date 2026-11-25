import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AuthService } from '../../core/auth.service';

function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  return group.get('password')?.value === group.get('confirm')?.value ? null : { mismatch: true };
}

@Component({
  selector: 'app-signup',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule,
    MatIconModule, MatProgressBarModule],
  template: `
    <div class="wrap">
      <mat-card class="box">
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        <div class="brand"><mat-icon>bloodtype</mat-icon> BloodNet</div>
        <h2>Create admin account</h2>

        @if (enabled() === false) {
          <p class="muted">Admin sign-up is turned off on this server. Ask an existing admin to add you from the Users page.</p>
          <a mat-stroked-button routerLink="/login" class="full">Back to sign in</a>
        } @else {
          <p class="muted">You need the setup code from whoever runs this server (ADMIN_SIGNUP_CODE).
            Once signed in you can add bank staff and hospital users.</p>
          <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-form-field class="full">
              <mat-label>Full name</mat-label>
              <input matInput formControlName="fullName" autocomplete="name" />
            </mat-form-field>
            <mat-form-field class="full">
              <mat-label>Email</mat-label>
              <input matInput formControlName="email" type="email" autocomplete="username" />
            </mat-form-field>
            <mat-form-field class="full">
              <mat-label>Password</mat-label>
              <input matInput formControlName="password" type="password" autocomplete="new-password" />
              <mat-hint>At least 8 characters</mat-hint>
            </mat-form-field>
            <mat-form-field class="full">
              <mat-label>Confirm password</mat-label>
              <input matInput formControlName="confirm" type="password" autocomplete="new-password" />
            </mat-form-field>
            @if (form.hasError('mismatch') && form.controls.confirm.touched) {
              <div class="error">Passwords don't match.</div>
            }
            <mat-form-field class="full">
              <mat-label>Setup code</mat-label>
              <input matInput formControlName="setupCode" type="password" autocomplete="off" />
            </mat-form-field>
            @if (error()) { <div class="error">{{ error() }}</div> }
            <button mat-flat-button color="primary" class="full" [disabled]="form.invalid || loading()">Create account</button>
            <a mat-button routerLink="/login" class="full">I already have an account</a>
          </form>
        }
      </mat-card>
    </div>
  `,
  styles: [`
    .wrap { min-height: 100vh; display: grid; place-items: center; background: var(--bn-bg); padding: 16px; }
    .box { width: 100%; max-width: 420px; padding: 24px; border: 1px solid var(--bn-border); box-shadow: 0 2px 12px rgba(60, 40, 40, .06); }
    .brand { display: flex; align-items: center; gap: 8px; font-size: 24px; font-weight: 600; color: var(--bn-red); }
    h2 { margin: 12px 0 4px; font-weight: 500; }
    .full { width: 100%; }
    form { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; }
    .error { color: var(--bn-red); font-size: 13px; }
  `],
})
export class SignupComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);

  readonly loading = signal(false);
  readonly error = signal('');
  readonly enabled = signal<boolean | null>(null);

  readonly form = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
    confirm: ['', Validators.required],
    setupCode: ['', Validators.required],
  }, { validators: passwordsMatch });

  constructor() {
    this.auth.signupStatus().subscribe({
      next: s => this.enabled.set(s.adminSignupEnabled),
      error: () => this.enabled.set(false),
    });
  }

  submit(): void {
    const { confirm, ...body } = this.form.getRawValue();
    this.loading.set(true);
    this.error.set('');
    this.auth.signupAdmin(body).subscribe({
      next: () => this.router.navigateByUrl('/users'),
      error: (e: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(e.status === 403 ? 'The setup code is not correct.'
          : e.status === 409 ? 'That email is already registered. Sign in instead.'
          : e.status === 0 ? 'Cannot reach the server. Please try again.'
          : e.error?.detail || e.error?.title || 'Could not create the account.');
      },
    });
  }
}
