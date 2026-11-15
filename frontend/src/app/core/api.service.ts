import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import {
  AllocationResult, AppNotification, Area, Bank, BloodRequest, Dashboard, Donation, Donor, Hospital, NearbyBank, Page,
  PublicBank, PublicBankStock,
  RebalanceSuggestion, Transfer, TransferStep, Unit, User, WastageRow,
} from './models';

/** Builds HttpParams, skipping null/undefined/empty values so optional filters are simply left out. */
function params(obj: Record<string, unknown>): HttpParams {
  let p = new HttpParams();
  for (const [k, v] of Object.entries(obj)) {
    if (v === null || v === undefined || v === '') continue;
    if (Array.isArray(v)) v.forEach(item => (p = p.append(k, String(item))));
    else p = p.set(k, String(v));
  }
  return p;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  // Banks rarely change, so one request per session is enough.
  private banks$?: Observable<Bank[]>;

  banks(): Observable<Bank[]> {
    this.banks$ ??= this.http.get<Bank[]>('/api/banks').pipe(shareReplay(1));
    return this.banks$;
  }

  nearby(q: { lat?: number; lng?: number; radiusKm?: number; bloodGroup?: string; component?: string; includeCompatible?: boolean }) {
    return this.http.get<NearbyBank[]>('/api/banks/nearby', { params: params(q) });
  }

  // inventory
  units(q: Record<string, unknown>) {
    return this.http.get<Page<Unit>>('/api/inventory/units', { params: params(q) });
  }
  discardUnit(id: number) {
    return this.http.post<Unit>(`/api/inventory/units/${id}/discard`, {});
  }
  rebalanceSuggestions() {
    return this.http.get<RebalanceSuggestion[]>('/api/inventory/rebalance-suggestions');
  }
  dismissSuggestion(id: number) {
    return this.http.post<void>(`/api/inventory/rebalance-suggestions/${id}/dismiss`, {});
  }

  // donors
  donors(q: string, page: number, size: number) {
    return this.http.get<Page<Donor>>('/api/donors', { params: params({ q, page, size }) });
  }
  registerDonor(body: Partial<Donor>) {
    return this.http.post<Donor>('/api/donors', body);
  }
  recordDonation(donorId: number, body: { collectedAt?: string; volumeMl: number; components?: string[] }) {
    return this.http.post<Donation>(`/api/donors/${donorId}/donations`, body);
  }
  donations(donorId: number) {
    return this.http.get<Donation[]>(`/api/donors/${donorId}/donations`);
  }

  // requests
  requests(q: { status?: string[]; page: number; size: number }) {
    return this.http.get<Page<BloodRequest>>('/api/requests', { params: params(q) });
  }
  createRequest(body: Record<string, unknown>) {
    return this.http.post<BloodRequest>('/api/requests', body);
  }
  allocate(id: number) {
    return this.http.post<AllocationResult>(`/api/requests/${id}/allocate`, {});
  }
  issue(id: number) {
    return this.http.post<BloodRequest>(`/api/requests/${id}/issue`, {});
  }
  cancelRequest(id: number) {
    return this.http.post<BloodRequest>(`/api/requests/${id}/cancel`, {});
  }
  myHospital() {
    return this.http.get<Hospital>('/api/hospitals/me');
  }
  hospitals() {
    return this.http.get<Hospital[]>('/api/hospitals');
  }

  // transfers
  transfers(direction: 'INCOMING' | 'OUTGOING') {
    return this.http.get<Transfer[]>('/api/transfers', { params: params({ direction }) });
  }
  createTransfer(body: Record<string, unknown>) {
    return this.http.post<Transfer>('/api/transfers', body);
  }
  moveTransfer(id: number, action: 'approve' | 'reject' | 'dispatch' | 'receive' | 'cancel') {
    return this.http.post<Transfer>(`/api/transfers/${id}/${action}`, {});
  }
  timeline(id: number) {
    return this.http.get<TransferStep[]>(`/api/transfers/${id}/timeline`);
  }

  // dashboard & reports
  dashboard(bankId?: number | null) {
    return this.http.get<Dashboard>('/api/dashboard', { params: params({ bankId }) });
  }
  expiring(days: number, bankId?: number | null) {
    return this.http.get<Unit[]>('/api/reports/expiring', { params: params({ days, bankId }) });
  }
  wastage(from: string, to: string, bankId?: number | null) {
    return this.http.get<WastageRow[]>('/api/reports/wastage', { params: params({ from, to, bankId }) });
  }

  // public page (no token needed)
  publicAvailability(q: { lat: number; lng: number; radiusKm: number; bloodGroup?: string | null; component?: string | null }) {
    return this.http.get<PublicBank[]>('/api/public/availability', { params: params(q) });
  }
  publicBank(id: number) {
    return this.http.get<PublicBankStock>(`/api/public/banks/${id}`);
  }
  publicAreas() {
    return this.http.get<Area[]>('/api/public/areas');
  }

  // notifications
  notifications() {
    return this.http.get<AppNotification[]>('/api/notifications');
  }
  unreadCount() {
    return this.http.get<{ unread: number }>('/api/notifications/unread-count');
  }
  readAll() {
    return this.http.post<{ updated: number }>('/api/notifications/read-all', {});
  }

  // users (admin)
  users() {
    return this.http.get<User[]>('/api/users');
  }
  createUser(body: Record<string, unknown>) {
    return this.http.post<User>('/api/users', body);
  }
}
