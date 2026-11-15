import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar } from '@angular/material/snack-bar';
import { debounceTime } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { GroupPipe, PrettyPipe } from '../../core/pipes';
import { BLOOD_GROUPS, Donation, Donor } from '../../core/models';

@Component({
  selector: 'app-donors',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, MatTableModule, MatPaginatorModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, MatCheckboxModule, GroupPipe, PrettyPipe],
  template: `
    <div class="page">
      <div class="page-head">
        <h1>Donors & donations</h1>
        <button mat-flat-button color="primary" (click)="showRegister.set(!showRegister())">
          <mat-icon>person_add</mat-icon> Register donor
        </button>
      </div>

      @if (showRegister()) {
        <form class="card" [formGroup]="donorForm" (ngSubmit)="register()" style="margin-bottom:16px">
          <h3>New donor</h3>
          <div class="form-grid">
            <mat-form-field><mat-label>Full name</mat-label><input matInput formControlName="fullName" /></mat-form-field>
            <mat-form-field>
              <mat-label>Blood group</mat-label>
              <mat-select formControlName="bloodGroup">
                @for (g of groups; track g) { <mat-option [value]="g">{{ g | group }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Gender</mat-label>
              <mat-select formControlName="gender">
                <mat-option value="FEMALE">Female</mat-option>
                <mat-option value="MALE">Male</mat-option>
                <mat-option value="OTHER">Other</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field><mat-label>Date of birth</mat-label><input matInput type="date" formControlName="dateOfBirth" /></mat-form-field>
            <mat-form-field>
              <mat-label>Phone</mat-label><input matInput formControlName="phone" />
              @if (donorForm.controls.phone.hasError('pattern')) { <mat-error>10-15 digits</mat-error> }
            </mat-form-field>
            <mat-form-field><mat-label>Email (optional)</mat-label><input matInput formControlName="email" /></mat-form-field>
          </div>
          <div class="actions">
            <button mat-flat-button color="primary" [disabled]="donorForm.invalid">Save donor</button>
            <button mat-button type="button" (click)="showRegister.set(false)">Cancel</button>
          </div>
        </form>
      }

      <div class="filters">
        <mat-form-field style="width:320px">
          <mat-label>Search name or phone</mat-label>
          <input matInput [formControl]="search" />
          <mat-icon matSuffix>search</mat-icon>
        </mat-form-field>
      </div>

      <div class="layout">
        <div class="table-wrap">
          <table mat-table [dataSource]="donors()">
            <ng-container matColumnDef="name">
              <th mat-header-cell *matHeaderCellDef>Name</th>
              <td mat-cell *matCellDef="let d">{{ d.fullName }}<div class="muted">{{ d.phone }}</div></td>
            </ng-container>
            <ng-container matColumnDef="group">
              <th mat-header-cell *matHeaderCellDef>Group</th>
              <td mat-cell *matCellDef="let d"><span class="group-badge">{{ d.bloodGroup | group }}</span></td>
            </ng-container>
            <ng-container matColumnDef="last">
              <th mat-header-cell *matHeaderCellDef>Last donation</th>
              <td mat-cell *matCellDef="let d">{{ (d.lastDonationAt | date: 'dd MMM yyyy') ?? 'Never' }}</td>
            </ng-container>
            <ng-container matColumnDef="eligible">
              <th mat-header-cell *matHeaderCellDef>Eligible</th>
              <td mat-cell *matCellDef="let d">
                @if (d.eligibleNow) { <span class="chip AVAILABLE">Yes</span> }
                @else { <span class="chip EXPIRED">From {{ d.nextEligibleAt | date: 'dd MMM' }}</span> }
              </td>
            </ng-container>
            <tr mat-header-row *matHeaderRowDef="columns"></tr>
            <tr mat-row *matRowDef="let row; columns: columns" (click)="select(row)" [class.selected]="row.id === selected()?.id"></tr>
          </table>
          <mat-paginator [length]="total()" [pageSize]="20" (page)="onPage($event)" />
        </div>

        <div class="card side">
          @if (selected(); as d) {
            <h3>{{ d.fullName }} <span class="group-badge">{{ d.bloodGroup | group }}</span></h3>
            <p class="muted">Born {{ d.dateOfBirth | date: 'dd MMM yyyy' }} · {{ d.gender | pretty }}</p>

            <h4>Record donation</h4>
            @if (!d.eligibleNow) {
              <p class="warn">Not eligible until {{ d.nextEligibleAt | date: 'dd MMM yyyy' }} (90-day rule or age limit).</p>
            }
            <form [formGroup]="donationForm" (ngSubmit)="recordDonation(d)">
              <mat-form-field class="full"><mat-label>Volume (ml)</mat-label><input matInput type="number" formControlName="volumeMl" /></mat-form-field>
              <div class="checks">
                <mat-checkbox formControlName="redCells">Red cells (42 days)</mat-checkbox>
                <mat-checkbox formControlName="platelets">Platelets (5 days)</mat-checkbox>
                <mat-checkbox formControlName="plasma">Plasma (1 year)</mat-checkbox>
              </div>
              <button mat-flat-button color="primary" [disabled]="!d.eligibleNow || donationForm.invalid">Record donation</button>
            </form>

            @if (lastCreated().length) {
              <h4>Units created</h4>
              @for (u of lastCreated(); track u.id) {
                <div class="unit">{{ u.unitCode }} · expires {{ u.expiresAt | date: 'dd MMM yyyy' }}</div>
              }
            }

            <h4>History</h4>
            @for (h of history(); track h.id) {
              <div class="unit">{{ h.collectedAt | date: 'dd MMM yyyy' }} · {{ h.volumeMl }} ml · {{ h.recordedBy }}</div>
            } @empty { <div class="muted">No donations yet</div> }
          } @else {
            <p class="muted">Select a donor to record a donation.</p>
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); gap: 16px; align-items: start; }
    @media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
    tr.mat-mdc-row { cursor: pointer; }
    tr.selected { background: #fff1f1; }
    .side h3 { display: flex; gap: 8px; align-items: center; margin: 0; }
    h4 { margin: 16px 0 8px; font-weight: 500; }
    .full { width: 100%; }
    .checks { display: flex; flex-direction: column; margin-bottom: 12px; }
    .unit { font-size: 13px; padding: 4px 0; border-bottom: 1px solid #f2eded; }
    .warn { color: #b71c1c; font-size: 13px; }
  `],
})
export class DonorsComponent implements OnInit {
  private api = inject(ApiService);
  private fb = inject(FormBuilder);
  private snack = inject(MatSnackBar);

  readonly groups = BLOOD_GROUPS;
  readonly columns = ['name', 'group', 'last', 'eligible'];
  readonly donors = signal<Donor[]>([]);
  readonly total = signal(0);
  readonly selected = signal<Donor | null>(null);
  readonly history = signal<Donation[]>([]);
  readonly lastCreated = signal<Donation['units']>([]);
  readonly showRegister = signal(false);
  readonly search = new FormControl('', { nonNullable: true });
  private page = 0;

  readonly donorForm = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(120)]],
    bloodGroup: ['', Validators.required],
    gender: ['FEMALE', Validators.required],
    dateOfBirth: ['', Validators.required],
    phone: ['', [Validators.required, Validators.pattern(/^\+?[0-9]{10,15}$/)]],
    email: ['', Validators.email],
  });

  readonly donationForm = this.fb.nonNullable.group({
    volumeMl: [450, [Validators.required, Validators.min(250), Validators.max(550)]],
    redCells: [true],
    platelets: [true],
    plasma: [true],
  });

  ngOnInit(): void {
    this.search.valueChanges.pipe(debounceTime(300)).subscribe(() => {
      this.page = 0;
      this.load();
    });
    this.load();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.load();
  }

  select(d: Donor): void {
    this.selected.set(d);
    this.lastCreated.set([]);
    this.api.donations(d.id).subscribe(h => this.history.set(h));
  }

  register(): void {
    const v = this.donorForm.getRawValue();
    this.api.registerDonor({ ...v, email: v.email || undefined } as Partial<Donor>).subscribe(d => {
      this.snack.open(`Registered ${d.fullName}`, undefined, { duration: 2500 });
      this.donorForm.reset();
      this.showRegister.set(false);
      this.load();
      this.select(d);
    });
  }

  recordDonation(d: Donor): void {
    const v = this.donationForm.getRawValue();
    const components = [v.redCells && 'RED_CELLS', v.platelets && 'PLATELETS', v.plasma && 'PLASMA'].filter(Boolean) as string[];
    if (!components.length) {
      this.snack.open('Pick at least one component', 'OK', { duration: 3000 });
      return;
    }
    this.api.recordDonation(d.id, { volumeMl: v.volumeMl, components }).subscribe(res => {
      this.snack.open(`Donation saved, ${res.units.length} units added to stock`, undefined, { duration: 3000 });
      this.lastCreated.set(res.units);
      this.load();
      this.api.donations(d.id).subscribe(h => this.history.set(h));
      this.selected.set({ ...d, eligibleNow: false, lastDonationAt: res.collectedAt });
    });
  }

  private load(): void {
    this.api.donors(this.search.value, this.page, 20).subscribe(p => {
      this.donors.set(p.content);
      this.total.set(p.totalElements);
    });
  }
}
