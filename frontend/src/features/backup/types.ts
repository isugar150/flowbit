export type BackupSection = "WORKSPACE" | "AUTH" | "NOTIFICATIONS" | "MEMBERS";

/** In the order they are listed on screen and restored. */
export const BACKUP_SECTIONS: BackupSection[] = [
  "WORKSPACE",
  "AUTH",
  "NOTIFICATIONS",
  "MEMBERS",
];

/**
 * The downloaded file (docs/features/admin-backup.md). The browser only reads what it needs to
 * list the contents; the settings themselves are in `data`, or in `encrypted` when a password
 * was set, and go back to the server untouched.
 */
export type BackupFile = {
  format: string;
  version: number;
  createdAt: string;
  sections: BackupSection[];
  data?: unknown;
  encrypted?: unknown;
};

export type RestoreResult = {
  restored: BackupSection[];
  membersAdded: number;
  membersSkipped: number;
  webhooks: number;
};

export const BACKUP_FORMAT = "flowbit-backup";
