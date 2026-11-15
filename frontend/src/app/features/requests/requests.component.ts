import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { FromNowPipe, GroupPipe, PrettyPipe } from '../../core/pipes';
import { BloodRequest } from '../../core/models';

const VIEWS: Record<string, string[]> = {
  open: ['PENDING', 'PARTIALLY_RESERVED', 'RESERVED'],
  done: ['ISSUED', 'CANCELLED'],
  all: [],
};

@Component({
  selector: 'app-requests',
  standalone: true,
  imports: [DatePipe, MatTableModule, MatPaginatorModule, MatButtonToggleModule, MatButtonModule, MatIconModule,
    MatTooltipModule, GroupPipe, PrettyPipe, FromNowPipe],
  template: `
    <div class="page">
      <div class="page-head">
        <h1>Requests & allocation</h1>
        <mat-button-toggle-group [value]="view()" (change)="setView($event.value)">
          <mat-button-toggle value="open">Open</mat-button-toggle>
          <mat-button-toggle value="done">Closed</mat-button-toggle>
          <mat-button-toggle value="all">All</mat-button-toggle>
        </mat-button-toggle-group>
      </div>

      <p class="muted">
        Allocate reserves compatible units (exact group first, then the soonest to expire) for 2 hours.
        If they are not issued in time, the reservation is released automatically.
      </p>

      <div class="table-wrap">
        <table mat-table [dataSource]="rows()">
          <ng-container matColumnDef="id">
            <th mat-header-cell *matHeaderCellDef>#</th>
            <td mat-cell *matCellDef="let r">{{ r.id }}</td>
          </ng-container>
          <ng-container matColumnDef="hospital">
            <th mat-header-cell *matHeaderCellDef>Hospital</th>
            <td mat-cell *matCellDef="let r">{{ r.hospitalName }}<div class="muted">Patient {{ r.patientRef }}</div></td>
          </ng-container>
          <ng-container matColumnDef="need">
            <th mat-header-cell *matHeaderCellDef>Need</th>
            <td mat-cell *matCellDef="let r">
              <span class="group-badge">{{ r.bloodGroup | group }}</span> {{ r.unitsRequested }} × {{ r.componentType | pretty }}
            </td>
          </ng-container>
          <ng-container matColumnDef="urgency">
            <th mat-header-cell *matHeaderCellDef>Urgency</th>
            <td mat-cell *matCellDef="let r"><span class="chip" [class]="r.urgency">{{ r.urgency | pretty }}</span></td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let r">
              <span class="chip" [class]="r.status">{{ r.status | pretty }}</span>
              <div class="muted">{{ r.unitsAllocated }}/{{ r.unitsRequested }} reserved</div>
              @if (r.reservedUntil) { <div class="muted">held until {{ r.reservedUntil | date: 'HH:mm' }} ({{ r.reservedUntil | fromNow }})</div> }
            </td>
          </ng-container>
          <ng-container matColumnDef="created">
            <th mat-header-cell *matHeaderCellDef>Raised</th>
            <td mat-cell *matCellDef="let r">{{ r.createdAt | date: 'dd MMM HH:mm' }}</td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let r">
              <div class="actions">
                @if (r.status === 'PENDING' || r.status === 'PARTIALLY_RESERVED') {
                  <button mat-flat-button color="primary" [disabled]="busy() === r.id" (click)="allocate(r)">Allocate</button>
                }
                @if (r.status === 'RESERVED' || r.status === 'PARTIALLY_RESERVED') {
                  <button mat-stroked-button [disabled]="busy() === r.id" (click)="issue(r)">Issue</button>
                }
                @if (r.status !== 'ISSUED' && r.status !== 'CANCELLED') {
                  <button mat-icon-button matTooltip="Cancel request" (click)="cancel(r)"><mat-icon>close</mat-icon></button>
                }
              </div>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns" [class.emergency]="row.urgency === 'EMERGENCY'"></tr>
          <tr class="mat-row" *matNoDataRow><td class="mat-cell empty" [attr.colspan]="columns.length">No requests</td></tr>
        </table>
        <mat-paginator [length]="total()" [pageSize]="20" (page)="onPage($event)" />
      </div>
    </div>
  `,
  styles: [`
    .empty { padding: 24px; text-align: center; color: var(--bn-muted); }
    tr.emergency { box-shadow: inset 4px 0 0 #b71c1c; }
  `],
})
export class RequestsComponent implements OnInit {
  private api = inject(ApiService);
  private snack = inject(MatSnackBar);

  readonly columns = ['id', 'hospital', 'need', 'urgency', 'status', 'created', 'actions'];
  readonly rows = signal<BloodRequest[]>([]);
  readonly total = signal(0);
  readonly view = signal<'open' | 'done' | 'all'>('open');
  readonly busy = signal<number | null>(null);
  private page = 0;

  ngOnInit(): void {
    this.load();
  }

  setView(v: 'open' | 'done' | 'all'): void {
    this.view.set(v);
    this.page = 0;
    this.load();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.load();
  }

  allocate(r: BloodRequest): void {
    this.busy.set(r.id);
    this.api.allocate(r.id).subscribe({
      next: res => {
        const msg = res.newlyReserved === 0
          ? 'No compatible units available right now. Try a nearby bank transfer.'
          : `Reserved ${res.newlyReserved} unit(s), ${res.totalAllocated}/${res.requested} in total`;
        this.snack.open(msg, 'OK', { duration: 4000 });
        this.busy.set(null);
        this.load();
      },
      error: () => this.busy.set(null),
    });
  }

  issue(r: BloodRequest): void {
    this.busy.set(r.id);
    this.api.issue(r.id).subscribe({
      next: () => {
        this.snack.open(`Request #${r.id} issued`, undefined, { duration: 2500 });
        this.busy.set(null);
        this.load();
      },
      error: () => this.busy.set(null),
    });
  }

  cancel(r: BloodRequest): void {
    if (!confirm(`Cancel request #${r.id}? Reserved units go back to stock.`)) return;
    this.api.cancelRequest(r.id).subscribe(() => this.load());
  }

  private load(): void {
    this.api.requests({ status: VIEWS[this.view()], page: this.page, size: 20 }).subscribe(p => {
      this.rows.set(p.content);
      this.total.set(p.totalElements);
    });
  }
}
