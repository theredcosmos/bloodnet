import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { GroupPipe, PrettyPipe } from '../../core/pipes';
import { BLOOD_GROUPS, Bank, COMPONENTS, NearbyBank, Transfer, TransferStatus } from '../../core/models';

type Action = 'approve' | 'reject' | 'dispatch' | 'receive' | 'cancel';

const STEPS: TransferStatus[] = ['REQUESTED', 'APPROVED', 'DISPATCHED', 'RECEIVED'];
const ACTION_FOR: Partial<Record<TransferStatus, Action>> = {
  APPROVED: 'approve', REJECTED: 'reject', DISPATCHED: 'dispatch', RECEIVED: 'receive', CANCELLED: 'cancel',
};

@Component({
  selector: 'app-transfers',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, MatTabsModule, MatButtonModule, MatIconModule, MatFormFieldModule,
    MatSelectModule, MatInputModule, GroupPipe, PrettyPipe],
  template: `
    <div class="page">
      <div class="page-head">
        <h1>Transfers</h1>
        @if (!auth.hasRole('ADMIN')) {
          <button mat-flat-button color="primary" (click)="showNew.set(!showNew())"><mat-icon>add</mat-icon> Ask a nearby bank</button>
        }
      </div>

      @if (showNew()) {
        <form class="card" [formGroup]="form" (ngSubmit)="create()" style="margin-bottom:16px">
          <div class="form-grid">
            <mat-form-field>
              <mat-label>Blood group</mat-label>
              <mat-select formControlName="bloodGroup" (selectionChange)="search()">
                @for (g of groups; track g) { <mat-option [value]="g">{{ g | group }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Component</mat-label>
              <mat-select formControlName="componentType" (selectionChange)="search()">
                @for (c of components; track c) { <mat-option [value]="c">{{ c | pretty }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Units</mat-label>
              <input matInput type="number" min="1" max="50" formControlName="units" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>From bank</mat-label>
              <mat-select formControlName="fromBankId">
                @for (b of nearby(); track b.bankId) {
                  <mat-option [value]="b.bankId">{{ b.name }} · {{ b.distanceKm }} km · {{ b.availableUnits }} units</mat-option>
                }
              </mat-select>
            </mat-form-field>
          </div>
          <mat-form-field style="width:100%"><mat-label>Notes</mat-label><input matInput formControlName="notes" /></mat-form-field>
          @if (searched() && !nearby().length) {
            <p class="muted">No other bank within 100 km holds this group and component.</p>
          }
          <button mat-flat-button color="primary" [disabled]="form.invalid || busy()">Send transfer request</button>
        </form>
      }

      @if (auth.hasRole('ADMIN')) {
        <!-- an admin has no bank of their own, so "incoming/outgoing" means nothing: show every transfer once -->
        <p class="muted">All transfers across the network, newest first.</p>
      } @else {
        <mat-tab-group (selectedIndexChange)="setTab($event)" [selectedIndex]="tab() === 'INCOMING' ? 0 : 1">
          <mat-tab label="Incoming (we receive)" />
          <mat-tab label="Outgoing (we send)" />
        </mat-tab-group>
      }

      <div class="list">
        @for (t of transfers(); track t.id) {
          <div class="card transfer">
            <div class="top">
              <div>
                <strong>#{{ t.id }}</strong>
                <span class="group-badge">{{ t.bloodGroup | group }}</span>
                {{ t.units }} × {{ t.componentType | pretty }}
                <div class="muted">{{ bankName(t.fromBankId) }} → {{ bankName(t.toBankId) }} · {{ t.createdAt | date: 'dd MMM HH:mm' }}</div>
                @if (t.notes) { <div class="muted">"{{ t.notes }}"</div> }
              </div>
              <span class="chip" [class]="t.status">{{ t.status | pretty }}</span>
            </div>

            <div class="steps" [class.stopped]="t.status === 'REJECTED' || t.status === 'CANCELLED'">
              @for (s of steps; track s; let i = $index) {
                <div class="step" [class.done]="stepIndex(t) >= i" [class.current]="stepIndex(t) === i">
                  <div class="dot">{{ stepIndex(t) > i ? '✓' : i + 1 }}</div>
                  <div class="label">{{ s | pretty }}</div>
                </div>
              }
            </div>

            <div class="actions">
              @for (next of actionsFor(t); track next) {
                <button mat-stroked-button [color]="next === 'REJECTED' || next === 'CANCELLED' ? 'warn' : 'primary'"
                        [disabled]="busy()" (click)="move(t, next)">{{ label(next) }}</button>
              }
            </div>
          </div>
        } @empty {
          <p class="muted">No transfers here.</p>
        }
      </div>
    </div>
  `,
  styles: [`
    .list { display: grid; gap: 12px; margin-top: 16px; }
    .top { display: flex; justify-content: space-between; gap: 12px; }
    .steps { display: flex; gap: 0; margin: 16px 0 12px; }
    .step { flex: 1; text-align: center; position: relative; color: #9e9e9e; font-size: 12px; }
    .step::before { content: ''; position: absolute; top: 13px; left: -50%; width: 100%; height: 2px; background: #e0e0e0; z-index: 0; }
    .step:first-child::before { display: none; }
    .step.done::before, .step.done .dot { background: var(--bn-red); color: #fff; }
    .step.done { color: #333; }
    .dot { width: 26px; height: 26px; border-radius: 50%; background: #e0e0e0; display: grid; place-items: center;
      margin: 0 auto 4px; position: relative; z-index: 1; font-weight: 600; }
    .step.current .dot { box-shadow: 0 0 0 4px #ecd9d9; }
    .steps.stopped { opacity: .45; }
  `],
})
export class TransfersComponent implements OnInit {
  readonly auth = inject(AuthService);
  private api = inject(ApiService);
  private fb = inject(FormBuilder);
  private snack = inject(MatSnackBar);

  readonly groups = BLOOD_GROUPS;
  readonly components = COMPONENTS;
  readonly steps = STEPS;
  readonly tab = signal<'INCOMING' | 'OUTGOING'>('INCOMING');
  readonly transfers = signal<Transfer[]>([]);
  readonly banks = signal<Bank[]>([]);
  readonly nearby = signal<NearbyBank[]>([]);
  readonly showNew = signal(false);
  readonly searched = signal(false);
  readonly busy = signal(false);
  private searchSeq = 0;
  private bankNames = computed(() => new Map(this.banks().map(b => [b.id, b.name])));

  readonly form = this.fb.group({
    bloodGroup: ['', Validators.required],
    componentType: ['RED_CELLS', Validators.required],
    units: [1, [Validators.required, Validators.min(1), Validators.max(50)]],
    fromBankId: [null as number | null, Validators.required],
    notes: [''],
  });

  ngOnInit(): void {
    this.api.banks().subscribe(b => this.banks.set(b));
    this.load();
  }

  setTab(i: number): void {
    this.tab.set(i === 0 ? 'INCOMING' : 'OUTGOING');
    this.load();
  }

  bankName(id: number): string {
    return this.bankNames().get(id) ?? `Bank #${id}`;
  }

  stepIndex(t: Transfer): number {
    return STEPS.indexOf(t.status);
  }

  /**
   * Show only the buttons this bank may press. The backend enforces the same rule;
   * this just keeps the UI honest: senders approve/dispatch, receivers confirm receipt.
   */
  actionsFor(t: Transfer): TransferStatus[] {
    const myBank = this.auth.user()?.bankId;
    const admin = this.auth.hasRole('ADMIN');
    const sender = admin || t.fromBankId === myBank;
    const receiver = admin || t.toBankId === myBank;
    return t.allowedNext.filter(s =>
      s === 'RECEIVED' ? receiver : s === 'CANCELLED' ? sender || receiver : sender);
  }

  label(s: TransferStatus): string {
    return { APPROVED: 'Approve', REJECTED: 'Reject', DISPATCHED: 'Dispatch', RECEIVED: 'Confirm received', CANCELLED: 'Cancel' }[s as string] ?? s;
  }

  move(t: Transfer, target: TransferStatus): void {
    const action = ACTION_FOR[target];
    if (!action) return;
    if ((action === 'reject' || action === 'cancel') && !confirm(`${this.label(target)} transfer #${t.id}?`)) return;
    this.busy.set(true);
    this.api.moveTransfer(t.id, action).subscribe({
      next: () => {
        this.busy.set(false);
        this.snack.open(`Transfer #${t.id}: ${target.toLowerCase()}`, undefined, { duration: 2500 });
        this.load();
      },
      error: () => {
        this.busy.set(false);
        this.load(); // someone else may have moved it first; show the current state
      },
    });
  }

  search(): void {
    const v = this.form.getRawValue();
    if (!v.bloodGroup || !v.componentType) return;
    const seq = ++this.searchSeq;
    // exact group only: we are moving stock between banks, not matching a patient
    this.api.nearby({ radiusKm: 100, bloodGroup: v.bloodGroup, component: v.componentType, includeCompatible: false })
      .subscribe(list => {
        if (seq !== this.searchSeq) return;
        this.searched.set(true);
        this.nearby.set(list);
        this.form.patchValue({ fromBankId: list.find(b => b.availableUnits > 0)?.bankId ?? null });
      });
  }

  create(): void {
    if (this.form.invalid || this.busy()) return;
    this.busy.set(true);
    this.api.createTransfer(this.form.getRawValue()).subscribe({
      next: t => {
        this.busy.set(false);
        this.snack.open(`Transfer #${t.id} requested`, undefined, { duration: 2500 });
        this.showNew.set(false);
        this.form.reset({ bloodGroup: '', componentType: 'RED_CELLS', units: 1, fromBankId: null, notes: '' });
        this.nearby.set([]);
        this.searched.set(false);
        this.tab.set('INCOMING');
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  private load(): void {
    const tab = this.tab();
    // drop the answer if the user switched tabs while it was loading
    this.api.transfers(tab).subscribe(list => { if (tab === this.tab()) this.transfers.set(list); });
  }
}
