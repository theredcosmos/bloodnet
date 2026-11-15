import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { combineLatest, interval, startWith, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { FromNowPipe, GroupPipe, PrettyPipe } from '../../core/pipes';
import { BLOOD_GROUPS, Bank, COMPONENTS, Dashboard, RebalanceSuggestion, StockCell } from '../../core/models';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, MatSelectModule, MatFormFieldModule, MatIconModule, MatButtonModule,
    GroupPipe, PrettyPipe, FromNowPipe],
  template: `
    <div class="page">
      <div class="page-head">
        <h1>Dashboard</h1>
        <div class="actions">
          @if (auth.hasRole('ADMIN')) {
            <mat-form-field>
              <mat-label>Bank</mat-label>
              <mat-select [ngModel]="bankId()" (ngModelChange)="bankId.set($event)">
                <mat-option [value]="null">All banks</mat-option>
                @for (b of banks(); track b.id) { <mat-option [value]="b.id">{{ b.name }}</mat-option> }
              </mat-select>
            </mat-form-field>
          }
          <span class="muted">Stock refreshed {{ data()?.refreshedAt | date: 'HH:mm:ss' }} · page updates every 60s</span>
        </div>
      </div>

      <div class="kpis">
        <div class="card kpi"><div class="label">Available units</div><div class="value">{{ data()?.totalAvailable ?? '-' }}</div></div>
        <div class="card kpi"><div class="label">Reserved</div><div class="value">{{ data()?.totalReserved ?? '-' }}</div></div>
        <div class="card kpi warn"><div class="label">Expiring in 48h</div><div class="value">{{ data()?.totalExpiring48h ?? '-' }}</div></div>
        <a class="card kpi link" routerLink="/requests">
          <div class="label">Pending requests</div>
          <div class="value">{{ pendingRequests() }}</div>
        </a>
        @if (data()?.openIncomingTransfers != null) {
          <a class="card kpi link" routerLink="/transfers">
            <div class="label">Open transfers in / out</div>
            <div class="value">{{ data()?.openIncomingTransfers }} / {{ data()?.openOutgoingTransfers }}</div>
          </a>
        }
      </div>

      <div class="card">
        <h3>Available stock by group</h3>
        <div class="table-wrap">
          <table class="matrix">
            <thead>
              <tr><th></th>@for (c of components; track c) { <th>{{ c | pretty }}</th> }</tr>
            </thead>
            <tbody>
              @for (g of groups; track g) {
                <tr>
                  <td><span class="group-badge">{{ g | group }}</span></td>
                  @for (c of components; track c) {
                    <td [class.low]="available(g, c) < 3">
                      <strong>{{ available(g, c) }}</strong>
                      @if (expiring(g, c)) { <span class="exp" title="expiring within 48h">{{ expiring(g, c) }} exp</span> }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="muted">Red cells mark fewer than 3 units on the shelf.</p>
      </div>

      @if (suggestions().length) {
        <div class="card" style="margin-top:16px">
          <h3><mat-icon inline>swap_horiz</mat-icon> Rebalance suggestions</h3>
          @for (s of suggestions(); track s.id) {
            <div class="suggestion">
              <span class="group-badge">{{ s.bloodGroup | group }}</span>
              <span>{{ s.unitCode }} ({{ s.componentType | pretty }}) expires {{ s.expiresAt | fromNow }}
                → {{ s.toBankName }}, {{ s.distanceKm }} km</span>
              <button mat-button (click)="dismiss(s)">Dismiss</button>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .kpis { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 16px; margin-bottom: 16px; }
    .kpi .label { color: var(--bn-muted); font-size: 13px; }
    .kpi .value { font-size: 30px; font-weight: 500; margin-top: 4px; }
    .kpi.warn .value { color: #e65100; }
    .kpi.link { text-decoration: none; color: inherit; }
    .kpi.link:hover { border-color: var(--bn-red); }
    h3 { margin: 0 0 12px; font-weight: 500; }
    table.matrix { border-collapse: collapse; width: 100%; }
    .matrix th, .matrix td { padding: 10px 12px; border-bottom: 1px solid var(--bn-border); text-align: center; }
    .matrix th { font-weight: 500; color: var(--bn-muted); font-size: 13px; }
    .matrix td.low { background: #fff1f1; }
    .exp { margin-left: 6px; font-size: 11px; color: #e65100; }
    .suggestion { display: flex; align-items: center; gap: 12px; padding: 6px 0; border-bottom: 1px solid #f2eded; font-size: 14px; }
    .suggestion span:nth-child(2) { flex: 1; }
  `],
})
export class DashboardComponent implements OnInit {
  readonly auth = inject(AuthService);
  private api = inject(ApiService);
  private destroyRef = inject(DestroyRef);

  readonly groups = BLOOD_GROUPS;
  readonly components = COMPONENTS;
  readonly bankId = signal<number | null>(null);
  readonly banks = signal<Bank[]>([]);
  readonly data = signal<Dashboard | null>(null);
  readonly suggestions = signal<RebalanceSuggestion[]>([]);
  readonly pendingRequests = computed(() => {
    const r = this.data()?.requests;
    return r ? r.PENDING + r.PARTIALLY_RESERVED : '-';
  });

  private cells = computed(() => {
    const map = new Map<string, StockCell>();
    this.data()?.stock.forEach(c => map.set(c.bloodGroup + '|' + c.componentType, c));
    return map;
  });

  constructor() {
    // Poll every 60s and whenever the admin switches bank. switchMap drops an in-flight call
    // if a newer one starts, so a slow response can't overwrite fresher data.
    combineLatest([toObservable(this.bankId), interval(60_000).pipe(startWith(0))])
      .pipe(switchMap(([bank]) => this.api.dashboard(bank)), takeUntilDestroyed())
      .subscribe(d => this.data.set(d));
  }

  ngOnInit(): void {
    if (this.auth.hasRole('ADMIN')) {
      this.api.banks().pipe(takeUntilDestroyed(this.destroyRef)).subscribe(b => this.banks.set(b));
    }
    this.loadSuggestions();
  }

  available(group: string, component: string): number {
    return this.cells().get(group + '|' + component)?.available ?? 0;
  }

  expiring(group: string, component: string): number {
    return this.cells().get(group + '|' + component)?.expiring48h ?? 0;
  }

  dismiss(s: RebalanceSuggestion): void {
    this.api.dismissSuggestion(s.id).subscribe(() => this.loadSuggestions());
  }

  private loadSuggestions(): void {
    this.api.rebalanceSuggestions().subscribe(list => this.suggestions.set(list));
  }
}
