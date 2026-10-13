import { apiFetch } from "@/lib/api/client";
import type { Me } from "@/features/auth/types";

/** Records that the tour was finished or skipped. The first time is the one that is kept. */
export function completeTour(): Promise<Me> {
  return apiFetch<Me>("/api/v1/me/tour/complete", { method: "POST" });
}
