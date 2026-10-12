import { apiFetch } from "@/lib/api/client";
import type { Credentials, Me } from "./types";

export function completeSetup(credentials: Credentials): Promise<Me> {
  return apiFetch<Me>("/api/v1/setup", { method: "POST", body: credentials });
}

export function login(credentials: Credentials): Promise<Me> {
  return apiFetch<Me>("/api/v1/auth/login", { method: "POST", body: credentials });
}

export function logout(): Promise<void> {
  return apiFetch<void>("/api/v1/auth/logout", { method: "POST" });
}
