import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatBadgeModule } from '@angular/material/badge';
import { MatMenuModule } from '@angular/material/menu';
import { BreakpointObserver } from '@angular/cdk/layout';
import { interval, startWith, switchMap } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../core/api.service';
import { AppNotification, Role } from '../core/models';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  roles: Role[];
}

const NAV: NavItem[] = [
  { path: '/dashboard', label: 'Dashboard', icon: 'space_dashboard', roles: ['ADMIN', 'BANK_STAFF'] },
  { path: '/inventory', label: 'Inventory', icon: 'inventory_2', roles: ['ADMIN', 'BANK_STAFF'] },
  { path: '/donors', label: 'Donors & donations', icon: 'volunteer_activism', roles: ['ADMIN', 'BANK_STAFF'] },
  { path: '/requests', label: 'Requests & allocation', icon: 'assignment', roles: ['ADMIN', 'BANK_STAFF'] },
  { path: '/transfers', label: 'Transfers', icon: 'local_shipping', roles: ['ADMIN', 'BANK_STAFF'] },
  { path: '/reports', label: 'Reports', icon: 'insights', roles: ['ADMIN', 'BANK_STAFF'] },
  { path: '/hospital', label: 'Hospital portal', icon: 'local_hospital', roles: ['HOSPITAL'] },
  { path: '/users', label: 'Users', icon: 'manage_accounts', roles: ['ADMIN'] },
];

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive, DatePipe, MatSidenavModule, MatToolbarModule, MatListModule,
    MatIconModule, MatButtonModule, MatBadgeModule, MatMenuModule,
  ],
  template: `
    <mat-sidenav-container class="container">
      <mat-sidenav [mode]="isMobile() ? 'over' : 'side'" [opened]="!isMobile()" #nav class="sidebar">
        <div class="brand">
          <mat-icon>bloodtype</mat-icon>
          <span>BloodNet</span>
        </div>
        <mat-nav-list>
          @for (item of menu(); track item.path) {
            <a mat-list-item [routerLink]="item.path" routerLinkActive="active" (click)="isMobile() && nav.close()">
              <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
              <span matListItemTitle>{{ item.label }}</span>
            </a>
          }
        </mat-nav-list>
        <div class="who">
          <div class="name">{{ auth.user()?.fullName }}</div>
          <div class="role">{{ auth.user()?.role }}</div>
        </div>
      </mat-sidenav>

      <mat-sidenav-content>
        <mat-toolbar class="topbar">
          @if (isMobile()) {
            <button mat-icon-button (click)="nav.toggle()" aria-label="Menu"><mat-icon>menu</mat-icon></button>
          }
          <span class="spacer"></span>
          <button mat-icon-button [matMenuTriggerFor]="inbox" (menuOpened)="openInbox()" aria-label="Notifications">
            <mat-icon [matBadge]="unread() || null" matBadgeColor="warn" matBadgeSize="small">notifications</mat-icon>
          </button>
          <mat-menu #inbox="matMenu" class="inbox-menu">
            @for (n of notifications(); track n.id) {
              <div class="note" [class.unread]="!n.read">
                <div>{{ n.message }}</div>
                <div class="muted">{{ n.createdAt | date: 'MMM d, HH:mm' }}</div>
              </div>
            } @empty {
              <div class="note muted">No notifications</div>
            }
          </mat-menu>
          <button mat-button (click)="auth.logout()"><mat-icon>logout</mat-icon> Sign out</button>
        </mat-toolbar>
        <router-outlet />
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
  styles: [`
    .container { height: 100vh; }
    .sidebar { width: 248px; background: var(--bn-sidebar); color: var(--bn-text); border-right: 1px solid var(--bn-border);
      display: flex; flex-direction: column; }
    .brand { display: flex; align-items: center; gap: 10px; padding: 20px 20px 12px; font-size: 20px; font-weight: 600; color: var(--bn-red); }
    .brand mat-icon { font-size: 28px; width: 28px; height: 28px; }
    .sidebar a.mat-mdc-list-item { --mdc-list-list-item-label-text-color: var(--bn-text);
      --mdc-list-list-item-leading-icon-color: var(--bn-muted); margin: 2px 8px; border-radius: 8px; width: auto; }
    .sidebar a.active { background: var(--bn-red-soft); --mdc-list-list-item-label-text-color: var(--bn-red);
      --mdc-list-list-item-leading-icon-color: var(--bn-red); }
    .who { margin-top: auto; padding: 16px 20px; border-top: 1px solid var(--bn-border); font-size: 13px; }
    .who .role { color: var(--bn-muted); font-size: 11px; letter-spacing: .5px; }
    .topbar { background: #fff; border-bottom: 1px solid var(--bn-border); position: sticky; top: 0; z-index: 2; }
    .spacer { flex: 1; }
    .note { padding: 10px 16px; max-width: 340px; font-size: 13px; border-bottom: 1px solid #f0eaea; }
    .note.unread { background: var(--bn-red-soft); }
  `],
})
export class ShellComponent implements OnInit {
  readonly auth = inject(AuthService);
  private api = inject(ApiService);
  private destroyRef = inject(DestroyRef);
  private breakpoints = inject(BreakpointObserver);

  readonly isMobile = signal(false);
  readonly unread = signal(0);
  readonly notifications = signal<AppNotification[]>([]);
  readonly menu = computed(() => NAV.filter(i => this.auth.hasRole(...i.roles)));

  ngOnInit(): void {
    this.breakpoints.observe('(max-width: 900px)')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(r => this.isMobile.set(r.matches));

    // light polling for the bell; a websocket would be overkill for this volume
    interval(60_000).pipe(
      startWith(0),
      switchMap(() => this.api.unreadCount()),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(r => this.unread.set(r.unread));
  }

  openInbox(): void {
    this.api.notifications().subscribe(list => {
      this.notifications.set(list);
      if (this.unread() > 0) {
        this.api.readAll().subscribe(() => this.unread.set(0));
      }
    });
  }
}
