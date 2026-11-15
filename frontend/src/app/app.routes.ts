import { Routes } from '@angular/router';
import { authGuard, homeGuard, roleGuard } from './core/guards';

const STAFF = ['ADMIN', 'BANK_STAFF'];

// Every feature is lazy-loaded, so the login page doesn't download the whole app.
export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.component').then(m => m.LoginComponent) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell.component').then(m => m.ShellComponent),
    children: [
      {
        path: 'dashboard', canActivate: [roleGuard], data: { roles: STAFF },
        loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent),
      },
      {
        path: 'inventory', canActivate: [roleGuard], data: { roles: STAFF },
        loadComponent: () => import('./features/inventory/inventory.component').then(m => m.InventoryComponent),
      },
      {
        path: 'donors', canActivate: [roleGuard], data: { roles: STAFF },
        loadComponent: () => import('./features/donors/donors.component').then(m => m.DonorsComponent),
      },
      {
        path: 'requests', canActivate: [roleGuard], data: { roles: STAFF },
        loadComponent: () => import('./features/requests/requests.component').then(m => m.RequestsComponent),
      },
      {
        path: 'transfers', canActivate: [roleGuard], data: { roles: STAFF },
        loadComponent: () => import('./features/transfers/transfers.component').then(m => m.TransfersComponent),
      },
      {
        path: 'reports', canActivate: [roleGuard], data: { roles: STAFF },
        loadComponent: () => import('./features/reports/reports.component').then(m => m.ReportsComponent),
      },
      {
        path: 'hospital', canActivate: [roleGuard], data: { roles: ['HOSPITAL'] },
        loadComponent: () => import('./features/hospital/hospital.component').then(m => m.HospitalComponent),
      },
      {
        path: 'users', canActivate: [roleGuard], data: { roles: ['ADMIN'] },
        loadComponent: () => import('./features/users/users.component').then(m => m.UsersComponent),
      },
      { path: '', pathMatch: 'full', canActivate: [homeGuard], children: [] },
    ],
  },
  { path: '**', redirectTo: '' },
];
