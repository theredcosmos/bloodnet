import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { FromNowPipe, GroupPipe, PrettyPipe } from '../../core/pipes';
import { Unit, WastageRow } from '../../core/models';

/** yyyy-MM-dd in the user's own timezone (toISOString() is UTC, which is "yesterday" early in the morning in IST). */
function isoDate(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Quotes a CSV field when it holds a comma, quote or newline. */
function csvField(v: unknown): string {
  const s = String(v ?? '');
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

@Component({
  selector: 'app-reports',
  standalone: true,
  imports: [DatePipe, FormsModule, MatTabsModule, MatTableModule, MatFormFieldModule, MatInputModule, MatButtonModule,
    MatIconModule, GroupPipe, PrettyPipe, FromNowPipe],
  template: `
    <div class="page">
      <div class="page-head"><h1>Reports</h1></div>
      <mat-tab-group>
        <mat-tab label="Expiring soon">
          <div class="filters pad">
            <mat-form-field>
              <mat-label>Within days</mat-label>
              <input matInput type="number" min="1" max="60" [(ngModel)]="days" />
            </mat-form-field>
            <button mat-flat-button color="primary" (click)="loadExpiring()">Run</button>
            <button mat-button (click)="exportExpiring()" [disabled]="!expiring().length"><mat-icon>download</mat-icon> CSV</button>
          </div>
          <div class="table-wrap">
            <table mat-table [dataSource]="expiring()">
              <ng-container matColumnDef="code"><th mat-header-cell *matHeaderCellDef>Unit</th>
                <td mat-cell *matCellDef="let u">{{ u.unitCode }}</td></ng-container>
              <ng-container matColumnDef="group"><th mat-header-cell *matHeaderCellDef>Group</th>
                <td mat-cell *matCellDef="let u"><span class="group-badge">{{ u.bloodGroup | group }}</span></td></ng-container>
              <ng-container matColumnDef="component"><th mat-header-cell *matHeaderCellDef>Component</th>
                <td mat-cell *matCellDef="let u">{{ u.componentType | pretty }}</td></ng-container>
              <ng-container matColumnDef="expires"><th mat-header-cell *matHeaderCellDef>Expires</th>
                <td mat-cell *matCellDef="let u">{{ u.expiresAt | date: 'dd MMM HH:mm' }} ({{ u.expiresAt | fromNow }})</td></ng-container>
              <tr mat-header-row *matHeaderRowDef="expCols"></tr>
              <tr mat-row *matRowDef="let r; columns: expCols"></tr>
              <tr class="mat-row" *matNoDataRow><td class="mat-cell empty" colspan="4">Nothing expiring in this window</td></tr>
            </table>
          </div>
        </mat-tab>

        <mat-tab label="Wastage">
          <div class="filters pad">
            <mat-form-field><mat-label>From</mat-label><input matInput type="date" [(ngModel)]="from" /></mat-form-field>
            <mat-form-field><mat-label>To</mat-label><input matInput type="date" [(ngModel)]="to" /></mat-form-field>
            <button mat-flat-button color="primary" (click)="loadWastage()">Run</button>
            <span class="muted">Total wasted: <strong>{{ wastedTotal() }}</strong> units</span>
          </div>
          <div class="table-wrap">
            <table mat-table [dataSource]="wastage()">
              <ng-container matColumnDef="group"><th mat-header-cell *matHeaderCellDef>Group</th>
                <td mat-cell *matCellDef="let w"><span class="group-badge">{{ w.bloodGroup | group }}</span></td></ng-container>
              <ng-container matColumnDef="component"><th mat-header-cell *matHeaderCellDef>Component</th>
                <td mat-cell *matCellDef="let w">{{ w.componentType | pretty }}</td></ng-container>
              <ng-container matColumnDef="reason"><th mat-header-cell *matHeaderCellDef>Reason</th>
                <td mat-cell *matCellDef="let w"><span class="chip" [class]="w.status">{{ w.status | pretty }}</span></td></ng-container>
              <ng-container matColumnDef="units"><th mat-header-cell *matHeaderCellDef>Units</th>
                <td mat-cell *matCellDef="let w">{{ w.units }}</td></ng-container>
              <tr mat-header-row *matHeaderRowDef="wasteCols"></tr>
              <tr mat-row *matRowDef="let r; columns: wasteCols"></tr>
              <tr class="mat-row" *matNoDataRow><td class="mat-cell empty" colspan="4">No wastage in this period</td></tr>
            </table>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`.pad { padding-top: 16px; } .empty { padding: 24px; text-align: center; color: var(--bn-muted); }`],
})
export class ReportsComponent implements OnInit {
  private api = inject(ApiService);
  private snack = inject(MatSnackBar);

  readonly expCols = ['code', 'group', 'component', 'expires'];
  readonly wasteCols = ['group', 'component', 'reason', 'units'];
  readonly expiring = signal<Unit[]>([]);
  readonly wastage = signal<WastageRow[]>([]);
  readonly wastedTotal = computed(() => this.wastage().reduce((sum, w) => sum + w.units, 0));

  days = 3;
  from = isoDate(new Date(Date.now() - 30 * 86_400_000));
  to = isoDate(new Date());

  ngOnInit(): void {
    this.loadExpiring();
    this.loadWastage();
  }

  loadExpiring(): void {
    if (!Number.isInteger(this.days) || this.days < 1 || this.days > 60) {
      this.snack.open('Days must be a whole number from 1 to 60', 'OK', { duration: 3000 });
      return;
    }
    this.api.expiring(this.days).subscribe(list => this.expiring.set(list));
  }

  loadWastage(): void {
    if (!this.from || !this.to || this.to < this.from) {
      this.snack.open('Pick a valid date range (To on or after From)', 'OK', { duration: 3000 });
      return;
    }
    this.api.wastage(this.from, this.to).subscribe(list => this.wastage.set(list));
  }

  exportExpiring(): void {
    const rows = [['unit_code', 'blood_group', 'component', 'expires_at'],
      ...this.expiring().map(u => [u.unitCode, u.bloodGroup, u.componentType, u.expiresAt])];
    const blob = new Blob([rows.map(r => r.map(csvField).join(',')).join('\n')], { type: 'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `expiring-${isoDate(new Date())}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    // revoking straight away can cancel the download in some browsers
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }
}
