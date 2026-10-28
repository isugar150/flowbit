export interface AuditActor {
  id: string;
  loginId: string;
  displayName: string | null;
}

export interface AuditLogEntry {
  id: string;
  occurredAt: string;
  type: string;
  actor: AuditActor | null;
  ipAddress: string | null;
  userAgent: string | null;
  detail: Record<string, string>;
}

/** One page of the log, newest first, with every kind it can hold. */
export interface AuditLogPage {
  total: number;
  types: string[];
  items: AuditLogEntry[];
}
