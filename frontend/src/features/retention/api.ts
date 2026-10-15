import { apiFetch } from "@/lib/api/client";
import type { RetentionSettings } from "./types";

export function saveRetention(settings: RetentionSettings): Promise<RetentionSettings> {
  return apiFetch<RetentionSettings>("/api/v1/admin/retention", {
    method: "PUT",
    body: settings,
  });
}
