import { apiFetch } from "@/lib/api/client";
import type { CleanupResult, StorageInspection } from "./types";

/** Reads the whole storage and the database, so it can take a few seconds. */
export function fetchFiles(): Promise<StorageInspection> {
  return apiFetch<StorageInspection>("/api/v1/admin/files");
}

/** Removes the files nothing uses that are old enough. */
export function cleanupFiles(): Promise<CleanupResult> {
  return apiFetch<CleanupResult>("/api/v1/admin/files/cleanup", { method: "POST" });
}
