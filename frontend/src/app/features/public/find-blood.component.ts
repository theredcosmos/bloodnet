import { Component, OnInit, inject, signal } from '@angular/core';
import { LowerCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { FromNowPipe, GroupPipe, PrettyPipe } from '../../core/pipes';
import { Area, BLOOD_GROUPS, COMPONENTS, PublicBank, PublicBankStock } from '../../core/models';

/**
 * Public page: anyone can see which nearby blood banks have stock, without logging in.
 * Shows counts, distance, phone and when the stock last changed. No donor or patient data.
 */
@Component({
  selector: 'app-find-blood',
  standalone: true,
  imports: [FormsModule, RouterLink, LowerCasePipe, MatFormFieldModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatProgressBarModule, MatTooltipModule, GroupPipe, PrettyPipe, FromNowPipe],
  template: `
    <header class="top">
      <div class="inner">
        <div class="brand"><mat-icon>bloodtype</mat-icon> BloodNet</div>
        @if (auth.isLoggedIn()) {
          <a mat-stroked-button [routerLink]="auth.homeUrl()">Open dashboard</a>
        } @else {
          <a mat-stroked-button routerLink="/login">Staff sign in</a>
        }
      </div>
    </header>

    <div class="emergency">
      <div class="inner">
        <mat-icon>emergency</mat-icon>
        <span>Medical emergency? Call <a href="tel:108"><strong>108</strong></a> for an ambulance or
          <a href="tel:112"><strong>112</strong></a> for emergency services.</span>
      </div>
    </div>

    <main class="inner">
      <h1>Find blood near you</h1>
      <p class="lede">Live stock from blood banks on the BloodNet network. Counts include every blood group that is
        safe for the patient you choose.</p>

      <section class="search">
        <mat-form-field>
          <mat-label>Patient's blood group</mat-label>
          <mat-select [(ngModel)]="group" (selectionChange)="search()">
            <mat-option [value]="null">Any group</mat-option>
            @for (g of groups; track g) { <mat-option [value]="g">{{ g | group }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Needed</mat-label>
          <mat-select [(ngModel)]="component" (selectionChange)="search()">
            @for (c of components; track c) { <mat-option [value]="c">{{ c | pretty }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Area</mat-label>
          <mat-select [(ngModel)]="areaKey" (selectionChange)="pickArea()">
            @if (myLocation()) { <mat-option value="me">My location</mat-option> }
            @for (a of areas(); track a.city) { <mat-option [value]="a.city">{{ a.city }} ({{ a.banks }})</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Within</mat-label>
          <mat-select [(ngModel)]="radiusKm" (selectionChange)="search()">
            @for (r of radii; track r) { <mat-option [value]="r">{{ r }} km</mat-option> }
          </mat-select>
        </mat-form-field>
        <button mat-flat-button color="primary" (click)="useMyLocation()" [disabled]="locating()">
          <mat-icon>my_location</mat-icon> Use my location
        </button>
      </section>

      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }

      <p class="summary">
        @if (results().length) {
          {{ withStock() }} of {{ results().length }} blood banks within {{ radiusKm }} km have
          {{ group ? (group | group) + '-compatible' : '' }} {{ component | pretty | lowercase }} available.
        } @else if (error()) {
          <span class="err">{{ error() }}</span>
        } @else if (!loading()) {
          No blood banks found within {{ radiusKm }} km. Try a wider distance.
        }
      </p>

      <div class="results">
        @for (b of results(); track b.bankId) {
          <article class="bank" [class.none]="b.availableUnits === 0">
            <div class="count" [attr.aria-label]="b.availableUnits + ' units available'">
              <span class="n">{{ b.availableUnits }}</span>
              <span class="u">{{ b.availableUnits === 1 ? 'unit' : 'units' }}</span>
            </div>
            <div class="info">
              <h2>{{ b.name }}</h2>
              <div class="meta">{{ place(b) }} · {{ b.distanceKm }} km away</div>
              <div class="meta">
                @if (b.stockUpdatedAt) { Stock last changed {{ b.stockUpdatedAt | fromNow }} }
                @else { No stock recorded yet }
              </div>
              <div class="actions">
                @if (b.phone) {
                  <a mat-stroked-button [href]="'tel:' + b.phone"><mat-icon>call</mat-icon> {{ b.phone }}</a>
                  <button mat-icon-button matTooltip="Copy number" (click)="copy(b.phone)" aria-label="Copy phone number">
                    <mat-icon>content_copy</mat-icon>
                  </button>
                }
                <a mat-button [href]="mapsUrl(b)" target="_blank" rel="noopener"><mat-icon>directions</mat-icon> Directions</a>
                <button mat-button (click)="toggle(b)">
                  <mat-icon>{{ open() === b.bankId ? 'expand_less' : 'expand_more' }}</mat-icon> Full stock
                </button>
              </div>

              @if (open() === b.bankId && detail(); as d) {
                <div class="grid-wrap">
                  <table class="grid">
                    <thead><tr><th>Group</th>@for (c of components; track c) { <th>{{ c | pretty }}</th> }</tr></thead>
                    <tbody>
                      @for (g of groups; track g) {
                        <tr>
                          <td><span class="group-badge">{{ g | group }}</span></td>
                          @for (c of components; track c) {
                            <td [class.zero]="!cell(d, g, c)">{{ cell(d, g, c) }}</td>
                          }
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </div>
          </article>
        }
      </div>

      <p class="note"><mat-icon inline>info</mat-icon>
        Stock changes minute to minute. Please call the blood bank to confirm before you travel.
        Only banks that use BloodNet are shown.</p>
    </main>
  `,
  styles: [`
    :host { display: block; min-height: 100vh; background: var(--bn-bg); }
    .inner { max-width: 960px; margin: 0 auto; padding-left: 16px; padding-right: 16px; }
    .top { background: #fff; border-bottom: 1px solid var(--bn-border); }
    .top .inner { display: flex; align-items: center; justify-content: space-between; height: 60px; }
    .brand { display: flex; align-items: center; gap: 8px; font-size: 20px; font-weight: 600; color: var(--bn-red); }
    .emergency { background: var(--bn-red-soft); color: var(--bn-red-dark); border-bottom: 1px solid #ead8d8; font-size: 14px; }
    .emergency .inner { display: flex; align-items: center; gap: 8px; padding-top: 10px; padding-bottom: 10px; }
    .emergency a { color: inherit; }
    h1 { font-size: 28px; font-weight: 500; margin: 28px 0 4px; }
    .lede { color: var(--bn-muted); margin: 0 0 20px; max-width: 62ch; }
    .search { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; background: #fff; border: 1px solid var(--bn-border);
      border-radius: 10px; padding: 16px; }
    .search mat-form-field { width: 180px; }
    .summary { color: var(--bn-muted); margin: 16px 0 8px; font-size: 14px; }
    .err { color: var(--bn-red); }
    .results { display: grid; gap: 12px; }
    .bank { display: grid; grid-template-columns: 88px minmax(0, 1fr); gap: 16px; background: #fff;
      border: 1px solid var(--bn-border); border-radius: 10px; padding: 16px; }
    .bank.none { opacity: .7; }
    .count { display: flex; flex-direction: column; align-items: center; justify-content: center; border-radius: 8px;
      background: var(--bn-red-soft); color: var(--bn-red); height: 72px; }
    .bank.none .count { background: #f1efee; color: var(--bn-muted); }
    .count .n { font-size: 28px; font-weight: 600; line-height: 1; font-variant-numeric: tabular-nums; }
    .count .u { font-size: 12px; margin-top: 4px; }
    h2 { font-size: 17px; font-weight: 500; margin: 0 0 4px; }
    .meta { color: var(--bn-muted); font-size: 13px; }
    .actions { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; margin-top: 8px; }
    .grid-wrap { overflow-x: auto; margin-top: 10px; }
    table.grid { border-collapse: collapse; font-size: 13px; min-width: 420px; }
    .grid th, .grid td { padding: 6px 10px; border-bottom: 1px solid var(--bn-border); text-align: center; }
    .grid th { color: var(--bn-muted); font-weight: 500; }
    .grid td.zero { color: #b9b2b0; }
    .note { color: var(--bn-muted); font-size: 13px; margin: 20px 0 40px; display: flex; gap: 6px; align-items: flex-start; }
    @media (max-width: 600px) {
      .search mat-form-field { width: 100%; }
      .bank { grid-template-columns: 64px minmax(0, 1fr); gap: 12px; }
      h1 { font-size: 23px; }
    }
  `],
})
export class FindBloodComponent implements OnInit {
  readonly auth = inject(AuthService);
  private api = inject(ApiService);
  private snack = inject(MatSnackBar);

  readonly groups = BLOOD_GROUPS;
  readonly components = COMPONENTS;
  readonly radii = [10, 25, 50, 100];

  readonly areas = signal<Area[]>([]);
  readonly results = signal<PublicBank[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly locating = signal(false);
  readonly myLocation = signal<{ lat: number; lng: number } | null>(null);
  readonly open = signal<number | null>(null);
  readonly detail = signal<PublicBankStock | null>(null);

  group: string | null = null;
  component = 'RED_CELLS';
  radiusKm = 25;
  areaKey = '';
  private center = { lat: 18.5204, lng: 73.8567 };

  ngOnInit(): void {
    // Start with the busiest area so the page shows real results straight away.
    this.api.publicAreas().subscribe(list => {
      this.areas.set(list);
      if (list.length) {
        this.areaKey = list[0].city;
        this.center = { lat: list[0].latitude, lng: list[0].longitude };
      }
      this.search();
    });
  }

  withStock(): number {
    return this.results().filter(b => b.availableUnits > 0).length;
  }

  useMyLocation(): void {
    if (!navigator.geolocation) {
      this.snack.open('Your browser cannot share location. Pick an area instead.', 'OK', { duration: 4000 });
      return;
    }
    this.locating.set(true);
    navigator.geolocation.getCurrentPosition(
      pos => {
        this.locating.set(false);
        this.myLocation.set({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        this.center = this.myLocation()!;
        this.areaKey = 'me';
        this.search();
      },
      () => {
        this.locating.set(false);
        this.snack.open('Location not available. Pick an area instead.', 'OK', { duration: 4000 });
      },
      { timeout: 10_000, maximumAge: 300_000 },
    );
  }

  pickArea(): void {
    if (this.areaKey === 'me' && this.myLocation()) {
      this.center = this.myLocation()!;
    } else {
      const a = this.areas().find(x => x.city === this.areaKey);
      if (a) this.center = { lat: a.latitude, lng: a.longitude };
    }
    this.search();
  }

  search(): void {
    this.loading.set(true);
    this.error.set('');
    this.open.set(null);
    this.api.publicAvailability({ ...this.center, radiusKm: this.radiusKm, bloodGroup: this.group, component: this.component })
      .subscribe({
        next: list => {
          this.results.set(list);
          this.loading.set(false);
        },
        error: (e: { status?: number }) => {
          this.loading.set(false);
          this.results.set([]);
          this.error.set(e.status === 429
            ? 'Too many searches from your network. Please wait a minute and try again.'
            : 'Could not load blood availability right now. Please try again.');
        },
      });
  }

  toggle(b: PublicBank): void {
    if (this.open() === b.bankId) {
      this.open.set(null);
      return;
    }
    this.detail.set(null);
    this.open.set(b.bankId);
    this.api.publicBank(b.bankId).subscribe(d => this.detail.set(d));
  }

  cell(d: PublicBankStock, group: string, component: string): number {
    return d.stock.find(s => s.bloodGroup === group && s.componentType === component)?.available ?? 0;
  }

  /** "Shivajinagar, Pune" rather than "Shivajinagar, Pune, Pune". */
  place(b: PublicBank): string {
    if (!b.address) return b.city;
    return b.address.toLowerCase().includes(b.city.toLowerCase()) ? b.address : `${b.address}, ${b.city}`;
  }

  mapsUrl(b: PublicBank): string {
    return `https://www.google.com/maps/dir/?api=1&destination=${b.latitude},${b.longitude}`;
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text).then(
      () => this.snack.open('Number copied', undefined, { duration: 1500 }),
      () => this.snack.open(text, 'OK', { duration: 4000 }),
    );
  }
}
