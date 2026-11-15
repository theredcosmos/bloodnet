import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { GroupPipe, PrettyPipe } from '../../core/pipes';
import { BLOOD_GROUPS, BloodRequest, COMPONENTS, Hospital, NearbyBank } from '../../core/models';

@Component({
  selector: 'app-hospital',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, MatFormFieldModule, MatSelectModule, MatInputModule, MatButtonModule,
    MatIconModule, MatTableModule, GroupPipe, PrettyPipe],
  template: `
    <div class="page">
      <div class="page-head">
        <h1>Hospital portal</h1>
        <span class="muted">{{ hospital()?.name }} · {{ hospital()?.city }}</span>
      </div>

      <div class="layout">
        <form class="card" [formGroup]="form" (ngSubmit)="submit()">
          <h3>1. What do you need?</h3>
          <div class="form-grid">
            <mat-form-field>
              <mat-label>Blood group</mat-label>
              <mat-select formControlName="bloodGroup" (selectionChange)="findBanks()">
                @for (g of groups; track g) { <mat-option [value]="g">{{ g | group }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Component</mat-label>
              <mat-select formControlName="componentType" (selectionChange)="findBanks()">
                @for (c of components; track c) { <mat-option [value]="c">{{ c | pretty }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Units</mat-label>
              <input matInput type="number" min="1" max="20" formControlName="units" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>Urgency</mat-label>
              <mat-select formControlName="urgency">
                <mat-option value="ROUTINE">Routine</mat-option>
                <mat-option value="URGENT">Urgent</mat-option>
                <mat-option value="EMERGENCY">Emergency</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Patient reference</mat-label>
              <input matInput formControlName="patientRef" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>Search radius (km)</mat-label>
              <input matInput type="number" formControlName="radiusKm" (change)="findBanks()" />
            </mat-form-field>
          </div>

          <h3>2. Pick a blood bank</h3>
          <p class="muted">Counts include compatible groups (e.g. O- for an O+ patient).</p>
          @for (b of nearby(); track b.bankId) {
            <label class="bank" [class.chosen]="form.value.bankId === b.bankId" [class.empty]="b.availableUnits === 0">
              <input type="radio" formControlName="bankId" [value]="b.bankId" />
              <div class="grow">
                <strong>{{ b.name }}</strong>
                <div class="muted">{{ b.city }} · {{ b.distanceKm }} km · {{ b.phone }}</div>
              </div>
              <div class="count">{{ b.availableUnits }}<span class="muted"> units</span></div>
            </label>
          } @empty {
            <p class="muted">Choose a group and component to see nearby banks.</p>
          }

          <div class="actions" style="margin-top:12px">
            <button mat-flat-button color="primary" [disabled]="form.invalid">Send request</button>
          </div>
        </form>

        <div class="card">
          <h3>My requests</h3>
          <table mat-table [dataSource]="mine()">
            <ng-container matColumnDef="what">
              <th mat-header-cell *matHeaderCellDef>Request</th>
              <td mat-cell *matCellDef="let r">
                #{{ r.id }} <span class="group-badge">{{ r.bloodGroup | group }}</span> {{ r.unitsRequested }} × {{ r.componentType | pretty }}
                <div class="muted">{{ r.bankName }} · {{ r.createdAt | date: 'dd MMM HH:mm' }}</div>
              </td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let r">
                <span class="chip" [class]="r.status">{{ r.status | pretty }}</span>
                <div class="muted">{{ r.unitsAllocated }}/{{ r.unitsRequested }}</div>
              </td>
            </ng-container>
            <ng-container matColumnDef="act">
              <th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let r">
                @if (r.status !== 'ISSUED' && r.status !== 'CANCELLED') {
                  <button mat-button (click)="cancel(r)">Cancel</button>
                }
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="['what', 'status', 'act']"></tr>
            <tr mat-row *matRowDef="let row; columns: ['what', 'status', 'act']"></tr>
          </table>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; align-items: start; }
    @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } }
    h3 { margin: 4px 0 12px; font-weight: 500; }
    .bank { display: flex; gap: 12px; align-items: center; padding: 10px 12px; border: 1px solid var(--bn-border);
      border-radius: 8px; margin-bottom: 8px; cursor: pointer; }
    .bank.chosen { border-color: var(--bn-red); background: #fff5f5; }
    .bank.empty { opacity: .6; }
    .grow { flex: 1; }
    .count { font-size: 22px; font-weight: 500; }
  `],
})
export class HospitalComponent implements OnInit {
  private api = inject(ApiService);
  private fb = inject(FormBuilder);
  private snack = inject(MatSnackBar);

  readonly groups = BLOOD_GROUPS;
  readonly components = COMPONENTS;
  readonly hospital = signal<Hospital | null>(null);
  readonly nearby = signal<NearbyBank[]>([]);
  readonly mine = signal<BloodRequest[]>([]);

  readonly form = this.fb.group({
    bloodGroup: ['', Validators.required],
    componentType: ['RED_CELLS', Validators.required],
    units: [1, [Validators.required, Validators.min(1), Validators.max(20)]],
    urgency: ['URGENT', Validators.required],
    patientRef: ['', [Validators.required, Validators.maxLength(60)]],
    radiusKm: [25],
    bankId: [null as number | null, Validators.required],
  });

  ngOnInit(): void {
    this.api.myHospital().subscribe(h => this.hospital.set(h));
    this.loadMine();
  }

  findBanks(): void {
    const h = this.hospital();
    const v = this.form.getRawValue();
    if (!h || !v.bloodGroup || !v.componentType) return;
    this.api.nearby({
      lat: h.latitude, lng: h.longitude, radiusKm: v.radiusKm ?? 25,
      bloodGroup: v.bloodGroup, component: v.componentType, includeCompatible: true,
    }).subscribe(list => {
      this.nearby.set(list);
      // pre-select the closest bank that actually has stock
      const best = list.find(b => b.availableUnits > 0);
      this.form.patchValue({ bankId: best?.bankId ?? null });
    });
  }

  submit(): void {
    const { radiusKm, ...body } = this.form.getRawValue();
    this.api.createRequest(body).subscribe(r => {
      this.snack.open(`Request #${r.id} sent to ${r.bankName}`, undefined, { duration: 3000 });
      this.form.patchValue({ patientRef: '' });
      this.loadMine();
    });
  }

  cancel(r: BloodRequest): void {
    if (!confirm(`Cancel request #${r.id}?`)) return;
    this.api.cancelRequest(r.id).subscribe(() => this.loadMine());
  }

  private loadMine(): void {
    this.api.requests({ page: 0, size: 50 }).subscribe(p => this.mine.set(p.content));
  }
}
