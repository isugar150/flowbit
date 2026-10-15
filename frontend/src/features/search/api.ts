import { apiFetch } from "@/lib/api/client";
import type { SearchResult } from "./types";

export const SEARCH_MAX_LENGTH = 100;

export function search(query: string, limit: number): Promise<SearchResult> {
  return apiFetch<SearchResult>(`/api/v1/search?q=${encodeURIComponent(query)}&limit=${limit}`);
}
