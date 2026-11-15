// Shapes returned by the Spring Boot API. Kept in one file so a backend change is easy to follow.

export type Role = 'ADMIN' | 'BANK_STAFF' | 'HOSPITAL';
export type BloodGroup = 'O_NEG' | 'O_POS' | 'A_NEG' | 'A_POS' | 'B_NEG' | 'B_POS' | 'AB_NEG' | 'AB_POS';
export type ComponentType = 'WHOLE_BLOOD' | 'RED_CELLS' | 'PLATELETS' | 'PLASMA';
export type UnitStatus = 'AVAILABLE' | 'RESERVED' | 'ISSUED' | 'IN_TRANSIT' | 'EXPIRED' | 'DISCARDED';
export type RequestStatus = 'PENDING' | 'PARTIALLY_RESERVED' | 'RESERVED' | 'ISSUED' | 'CANCELLED';
export type Urgency = 'ROUTINE' | 'URGENT' | 'EMERGENCY';
export type TransferStatus = 'REQUESTED' | 'APPROVED' | 'DISPATCHED' | 'RECEIVED' | 'REJECTED' | 'CANCELLED';

export const BLOOD_GROUPS: BloodGroup[] = ['O_NEG', 'O_POS', 'A_NEG', 'A_POS', 'B_NEG', 'B_POS', 'AB_NEG', 'AB_POS'];
export const COMPONENTS: ComponentType[] = ['RED_CELLS', 'PLATELETS', 'PLASMA', 'WHOLE_BLOOD'];
export const UNIT_STATUSES: UnitStatus[] = ['AVAILABLE', 'RESERVED', 'ISSUED', 'IN_TRANSIT', 'EXPIRED', 'DISCARDED'];

export function groupLabel(g: string | null | undefined): string {
  return g ? g.replace('_NEG', '-').replace('_POS', '+') : '';
}

export interface User {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  bankId?: number;
  hospitalId?: number;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  user: User;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Bank {
  id: number;
  code: string;
  name: string;
  city: string;
  address?: string;
  phone?: string;
  latitude: number;
  longitude: number;
}

export interface NearbyBank {
  bankId: number;
  code: string;
  name: string;
  city: string;
  phone?: string;
  distanceKm: number;
  availableUnits: number;
}

export interface Unit {
  id: number;
  unitCode: string;
  bankId: number;
  bloodGroup: BloodGroup;
  componentType: ComponentType;
  status: UnitStatus;
  collectedAt: string;
  expiresAt: string;
  requestId?: number;
  reservedUntil?: string;
  transferId?: number;
}

export interface Donor {
  id: number;
  fullName: string;
  bloodGroup: BloodGroup;
  gender: string;
  dateOfBirth: string;
  phone: string;
  email?: string;
  registeredBankId: number;
  lastDonationAt?: string;
  nextEligibleAt?: string;
  eligibleNow: boolean;
}

export interface Donation {
  id: number;
  donorId: number;
  bankId: number;
  collectedAt: string;
  volumeMl: number;
  recordedBy: string;
  units: Unit[];
}

export interface BloodRequest {
  id: number;
  hospitalId: number;
  hospitalName: string;
  bankId: number;
  bankName: string;
  bloodGroup: BloodGroup;
  componentType: ComponentType;
  unitsRequested: number;
  unitsAllocated: number;
  urgency: Urgency;
  status: RequestStatus;
  patientRef: string;
  notes?: string;
  reservedUntil?: string;
  createdBy: string;
  createdAt: string;
}

export interface AllocationResult {
  requestId: number;
  newlyReserved: number;
  totalAllocated: number;
  requested: number;
  status: RequestStatus;
  unitIds: number[];
}

export interface Hospital {
  id: number;
  name: string;
  city: string;
  latitude: number;
  longitude: number;
}

export interface Transfer {
  id: number;
  fromBankId: number;
  toBankId: number;
  bloodGroup: BloodGroup;
  componentType: ComponentType;
  units: number;
  status: TransferStatus;
  allowedNext: TransferStatus[];
  notes?: string;
  requestedBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface TransferStep {
  fromStatus?: TransferStatus;
  toStatus: TransferStatus;
  actor: string;
  at: string;
}

export interface StockCell {
  bloodGroup: BloodGroup;
  componentType: ComponentType;
  available: number;
  reserved: number;
  expiring48h: number;
}

export interface Dashboard {
  bankId?: number;
  refreshedAt?: string;
  totalAvailable: number;
  totalReserved: number;
  totalExpiring48h: number;
  stock: StockCell[];
  requests: Record<RequestStatus, number>;
  openIncomingTransfers?: number;
  openOutgoingTransfers?: number;
}

export interface WastageRow {
  bankId: number;
  bloodGroup: BloodGroup;
  componentType: ComponentType;
  status: UnitStatus;
  units: number;
}

export interface RebalanceSuggestion {
  id: number;
  unitId: number;
  unitCode: string;
  bloodGroup: BloodGroup;
  componentType: ComponentType;
  expiresAt: string;
  fromBankId: number;
  toBankId: number;
  toBankName: string;
  distanceKm: number;
  reason: string;
}

export interface AppNotification {
  id: number;
  type: string;
  message: string;
  read: boolean;
  createdAt: string;
}
