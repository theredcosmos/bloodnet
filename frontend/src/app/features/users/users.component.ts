import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { Bank, Hospital, User } from '../../core/models';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [ReactiveFormsModule, MatTableModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule],
  template: `
    <div class="page">
      <div class="page-head"><h1>Users</h1></div>

      <form class="card" [formGroup]="form" (ngSubmit)="create()" style="margin-bottom:16px">
        <h3 style="margin-top:0">Add user</h3>
        <div class="form-grid">
          <mat-form-field><mat-label>Full name</mat-label><input matInput formControlName="fullName" /></mat-form-field>
          <mat-form-field><mat-label>Email</mat-label><input matInput formControlName="email" /></mat-form-field>
          <mat-form-field>
            <mat-label>Temporary password</mat-label><input matInput type="password" formControlName="password" />
            <mat-hint>At least 8 characters</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Role</mat-label>
            <mat-select formControlName="role">
              <mat-option value="BANK_STAFF">Bank staff</mat-option>
              <mat-option value="HOSPITAL">Hospital</mat-option>
              <mat-option value="ADMIN">Admin</mat-option>
            </mat-select>
          </mat-form-field>
          @if (form.value.role === 'BANK_STAFF') {
            <mat-form-field>
              <mat-label>Bank</mat-label>
              <mat-select formControlName="bankId">
                @for (b of banks(); track b.id) { <mat-option [value]="b.id">{{ b.name }}</mat-option> }
              </mat-select>
            </mat-form-field>
          }
          @if (form.value.role === 'HOSPITAL') {
            <mat-form-field>
              <mat-label>Hospital</mat-label>
              <mat-select formControlName="hospitalId">
                @for (h of hospitals(); track h.id) { <mat-option [value]="h.id">{{ h.name }}</mat-option> }
              </mat-select>
            </mat-form-field>
          }
        </div>
        <button mat-flat-button color="primary" [disabled]="form.invalid">Create user</button>
      </form>

      <div class="table-wrap">
        <table mat-table [dataSource]="users()">
          <ng-container matColumnDef="name"><th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let u">{{ u.fullName }}<div class="muted">{{ u.email }}</div></td></ng-container>
          <ng-container matColumnDef="role"><th mat-header-cell *matHeaderCellDef>Role</th>
            <td mat-cell *matCellDef="let u">{{ u.role }}</td></ng-container>
          <ng-container matColumnDef="scope"><th mat-header-cell *matHeaderCellDef>Bank / hospital</th>
            <td mat-cell *matCellDef="let u">{{ scope(u) }}</td></ng-container>
          <tr mat-header-row *matHeaderRowDef="cols"></tr>
          <tr mat-row *matRowDef="let r; columns: cols"></tr>
        </table>
      </div>
    </div>
  `,
})
export class UsersComponent implements OnInit {
  private api = inject(ApiService);
  private fb = inject(FormBuilder);
  private snack = inject(MatSnackBar);

  readonly cols = ['name', 'role', 'scope'];
  readonly users = signal<User[]>([]);
  readonly banks = signal<Bank[]>([]);
  readonly hospitals = signal<Hospital[]>([]);

  readonly form = this.fb.group({
    fullName: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
    role: ['BANK_STAFF', Validators.required],
    bankId: [null as number | null],
    hospitalId: [null as number | null],
  });

  ngOnInit(): void {
    this.api.banks().subscribe(b => this.banks.set(b));
    this.api.hospitals().subscribe(h => this.hospitals.set(h));
    this.load();
  }

  scope(u: User): string {
    if (u.bankId) return this.banks().find(b => b.id === u.bankId)?.name ?? `Bank #${u.bankId}`;
    if (u.hospitalId) return this.hospitals().find(h => h.id === u.hospitalId)?.name ?? `Hospital #${u.hospitalId}`;
    return 'All banks';
  }

  create(): void {
    this.api.createUser(this.form.getRawValue()).subscribe(u => {
      this.snack.open(`Created ${u.email}`, undefined, { duration: 2500 });
      this.form.reset({ role: 'BANK_STAFF' });
      this.load();
    });
  }

  private load(): void {
    this.api.users().subscribe(u => this.users.set(u));
  }
}
