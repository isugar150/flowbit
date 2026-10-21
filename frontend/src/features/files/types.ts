/** How many files and how many bytes they take up. */
export interface Tally {
  count: number;
  bytes: number;
}

export interface AreaTallies {
  files: Tally;
  images: Tally;
  originals: Tally;
  /** The three together. */
  total: Tally;
}

export type StorageArea = "FILES" | "IMAGES" | "ORIGINALS";

export type FileSource = "TICKET_ATTACHMENT" | "TICKET_COMMENT" | "CONTENT_COMMENT";

export interface StorageInfo {
  type: "LOCAL" | "S3";
  location: string;
  region: string | null;
  endpoint: string | null;
}

export interface FileUses {
  ticketAttachments: Tally;
  ticketCommentFiles: Tally;
  contentCommentFiles: Tally;
  pendingFiles: Tally;
  avatars: Tally;
  logo: Tally;
  projectIcons: Tally;
  contentImages: Tally;
  awaitingPurge: Tally;
}

export interface Uploader {
  userId: string;
  name: string;
  count: number;
  bytes: number;
}

export interface LargeFile {
  name: string;
  bytes: number;
  uploader: string;
  source: FileSource;
  issueKey: string | null;
  createdAt: string;
}

export interface OrphanSample {
  area: StorageArea;
  key: string;
  bytes: number;
  modifiedAt: string;
  cleanable: boolean;
}

export interface MissingSample {
  kind: "FILE" | "IMAGE";
  name: string | null;
  key: string;
  source: FileSource | null;
  issueKey: string | null;
}

export interface Consistency {
  graceHours: number;
  orphans: AreaTallies;
  cleanable: Tally;
  inGrace: number;
  orphanSamples: OrphanSample[];
  missing: { files: number; images: number };
  missingSamples: MissingSample[];
  imagesWithoutOriginal: number;
  unknownObjects: number;
}

/** Everything the administrator's file page shows (docs/features/admin-files.md). */
export interface StorageInspection {
  checkedAt: string;
  storage: StorageInfo;
  /** The largest file the server takes (FLOWBIT_MAX_UPLOAD_SIZE). */
  maxUploadBytes: number;
  areas: AreaTallies;
  total: Tally;
  uses: FileUses;
  topUploaders: Uploader[];
  largestFiles: LargeFile[];
  consistency: Consistency;
}

export interface CleanupResult {
  deleted: AreaTallies;
  bytes: number;
  failed: number;
}
