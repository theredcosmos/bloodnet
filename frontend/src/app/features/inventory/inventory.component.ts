import { Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSort, MatSortModule, Sort } from '@angular/material/sort';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, catchError, debounceTime, of, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { FromNowPipe, GroupPipe, PrettyPipe } from '../../core/pipes';
import { BLOOD_GROUPS, Bank, COMPONENTS, UNIT_STATUSES, Unit } from '../../core/models';

@Component({
  selector: 'app-inventory',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule, MatTableModule, MatPaginatorModule, MatSortModule, MatSelectModule,
    MatInputModule, MatFormFieldModule, MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule,
    GroupPipe, PrettyPipe, FromNowPipe],
  template: `
    <div class="page">
      <div class="page-head"><h1>Inventory</h1><span class="muted">{{ total() }} units match</span></div>

      <form class="filters" [formGroup]="filters">
        @if (auth.hasRole('ADMIN')) {
          <mat-form-field>
            <mat-label>Bank</mat-label>
            <mat-select formControlName="bankId">
              <mat-option [value]="null">All</mat-option>
              @for (b of banks(); track b.id) { <mat-option [value]="b.id">{{ b.name }}</mat-option> }
            </mat-select>
          </mat-form-field>
        }
        <mat-form-field>
          <mat-label>Group</mat-label>
          <mat-select formControlName="bloodGroup">
            <mat-option [value]="null">All</mat-option>
            @for (g of groups; track g) { <mat-option [value]="g">{{ g | group }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Component</mat-label>
          <mat-select formControlName="component">
            <mat-option [value]="null">All</mat-option>
            @for (c of components; track c) { <mat-option [value]="c">{{ c | pretty }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Status</mat-label>
          <mat-select formControlName="status">
            <mat-option [value]="null">All</mat-option>
            @for (s of statuses; track s) { <mat-option [value]="s">{{ s | pretty }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Expiring within (days)</mat-label>
          <input matInput type="number" min="0" formControlName="expiringWithinDays" />
        </mat-form-field>
        <mat-form-field>
          <mat-label>Unit code</mat-label>
          <input matInput formControlName="code" />
        </mat-form-field>
        <button mat-button type="button" (click)="clearFilters()">Clear</button>
      </form>

      <div class="table-wrap">
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        <table mat-table [dataSource]="rows()" matSort matSortActive="expiresAt" matSortDirection="asc"
               (matSortChange)="onSort($event)">
          <ng-container matColumnDef="unitCode">
            <th mat-header-cell *matHeaderCellDef mat-sort-header>Unit</th>
            <td mat-cell *matCellDef="let u">{{ u.unitCode }}</td>
          </ng-container>
          <ng-container matColumnDef="bloodGroup">
            <th mat-header-cell *matHeaderCellDef mat-sort-header>Group</th>
            <td mat-cell *matCellDef="let u"><span class="group-badge">{{ u.bloodGroup | group }}</span></td>
          </ng-container>
          <ng-container matColumnDef="componentType">
            <th mat-header-cell *matHeaderCellDef mat-sort-header>Component</th>
            <td mat-cell *matCellDef="let u">{{ u.componentType | pretty }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef mat-sort-header>Status</th>
            <td mat-cell *matCellDef="let u"><span class="chip" [class]="u.status">{{ u.status | pretty }}</span></td>
          </ng-container>
          <ng-container matColumnDef="collectedAt">
            <th mat-header-cell *matHeaderCellDef mat-sort-header>Collected</th>
            <td mat-cell *matCellDef="let u">{{ u.collectedAt | date: 'dd MMM yyyy' }}</td>
          </ng-container>
          <ng-container matColumnDef="expiresAt">
            <th mat-header-cell *matHeaderCellDef mat-sort-header>Expires</th>
            <td mat-cell *matCellDef="let u" [matTooltip]="(u.expiresAt | date: 'medium') ?? ''">{{ u.expiresAt | fromNow }}</td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let u">
              @if (u.status === 'AVAILABLE') {
                <button mat-icon-button matTooltip="Discard (quality issue)" (click)="discard(u)"><mat-icon>delete_outline</mat-icon></button>
              }
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns; sticky: true"></tr>
          <tr mat-row *matRowDef="let row; columns: columns"></tr>
          <tr class="mat-row" *matNoDataRow><td class="mat-cell empty" [attr.colspan]="columns.length">No units found</td></tr>
        </table>
        <mat-paginator [length]="total()" [pageSize]="size" [pageSizeOptions]="[10, 20, 50, 100]"
                       (page)="onPage($event)" showFirstLastButtons />
      </div>
    </div>
  `,
  styles: [`.empty { padding: 24px; text-align: center; color: var(--bn-muted); }`],
})
export class InventoryComponent implements OnInit {
  readonly auth = inject(AuthService);
  private api = inject(ApiService);
  private fb = inject(FormBuilder);

  @ViewChild(MatPaginator) paginator?: MatPaginator;
  @ViewChild(MatSort) sorter?: MatSort;

  readonly groups = BLOOD_GROUPS;
  readonly components = COMPONENTS;
  readonly statuses = UNIT_STATUSES;
  readonly columns = ['unitCode', 'bloodGroup', 'componentType', 'status', 'collectedAt', 'expiresAt', 'actions'];

  readonly rows = signal<Unit[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly banks = signal<Bank[]>([]);

  readonly filters = this.fb.group({
    bankId: [null as number | null],
    bloodGroup: [null as string | null],
    component: [null as string | null],
    status: ['AVAILABLE' as string | null],
    expiringWithinDays: [null as number | null],
    code: [''],
  });

  page = 0;
  size = 20;
  sort = 'expiresAt,asc';

  // Every reload goes through one stream: switchMap drops a stale in-flight call, so a slow
  // response for old filters can never overwrite the rows for the current ones.
  private readonly reload$ = new Subject<void>();

  constructor() {
    this.reload$.pipe(
      switchMap(() => {
        this.loading.set(true);
        return this.api.units({ ...this.filters.getRawValue(), page: this.page, size: this.size, sort: this.sort })
          .pipe(catchError(() => of(null)));
      }),
      takeUntilDestroyed(),
    ).subscribe(p => {
      this.loading.set(false);
      if (!p) return;
      this.rows.set(p.content);
      this.total.set(p.totalElements);
    });
    this.filters.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => {
      this.firstPage();
      this.load();
    });
  }

  ngOnInit(): void {
    if (this.auth.hasRole('ADMIN')) this.api.banks().subscribe(b => this.banks.set(b));
    this.load();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.load();
  }

  onSort(s: Sort): void {
    this.sort = s.direction ? `${s.active},${s.direction}` : 'expiresAt,asc';
    this.firstPage();
    this.load();
  }

  clearFilters(): void {
    this.filters.reset({ status: 'AVAILABLE', code: '' });
  }

  discard(u: Unit): void {
    if (!confirm(`Discard unit ${u.unitCode}? This cannot be undone.`)) return;
    this.api.discardUnit(u.id).subscribe(() => this.load());
  }

  /** Back to page 1. Sets the index directly: paginator.firstPage() would emit (page) and load twice. */
  private firstPage(): void {
    this.page = 0;
    if (this.paginator) this.paginator.pageIndex = 0;
  }

  private load(): void {
    this.reload$.next();
  }
}
