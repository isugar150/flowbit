import { apiFetch } from "@/lib/api/client";
import type { BackupFile, BackupSection, RestoreResult } from "./types";

const ADMIN = "/api/v1/admin/backup";

/** An empty password leaves the file unencrypted. */
export function exportBackup(input: {
  sections: BackupSection[];
  password: string;
}): Promise<BackupFile> {
  return apiFetch<BackupFile>(`${ADMIN}/export`, {
    method: "POST",
    body: { sections: input.sections, password: input.password || null },
  });
}

export function restoreBackup(input: {
  file: BackupFile;
  sections: BackupSection[];
  password: string;
}): Promise<RestoreResult> {
  return apiFetch<RestoreResult>(`${ADMIN}/restore`, {
    method: "POST",
    body: {
      file: input.file,
      sections: input.sections,
      password: input.password || null,
    },
  });
}
