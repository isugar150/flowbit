import type { Me } from "@/features/auth/types";
import type { Workspace } from "@/features/settings/types";

/**
 * Server-side calls to the backend, used by proxy.ts and server components. These go straight
 * to the backend (not through the /api rewrite) and forward the browser's cookies.
 */

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export const SESSION_COOKIE = "FLOWBIT_SESSION";

/** Thrown when the backend cannot be reached or answers with something unexpected. */
export class BackendUnavailableError extends Error {
  constructor(cause?: unknown) {
    super("Backend unavailable", { cause });
    this.name = "BackendUnavailableError";
  }
}

async function get(path: string, cookieHeader?: string): Promise<Response> {
  try {
    return await fetch(`${BACKEND_URL}${path}`, {
      headers: { Accept: "application/json", ...(cookieHeader ? { Cookie: cookieHeader } : {}) },
      cache: "no-store",
      signal: AbortSignal.timeout(5000),
    });
  } catch (error) {
    throw new BackendUnavailableError(error);
  }
}

export async function fetchSetupCompleted(): Promise<boolean> {
  const response = await get("/api/v1/setup/status");
  if (!response.ok) throw new BackendUnavailableError(response.status);
  const body = (await response.json()) as { completed: boolean };
  return body.completed;
}

/**
 * GET a JSON resource on behalf of the signed-in user. Returns null when the backend says the
 * user is signed out, may not see it, or it does not exist; the caller decides what that means.
 */
export async function fetchJson<T>(path: string, cookieHeader: string): Promise<T | null> {
  const response = await get(path, cookieHeader);
  if (response.status === 401 || response.status === 403 || response.status === 404) return null;
  if (!response.ok) throw new BackendUnavailableError(response.status);
  return (await response.json()) as T;
}

/** Name and logo of this installation. Needs a valid session. */
export async function fetchWorkspace(cookieHeader: string): Promise<Workspace | null> {
  const response = await get("/api/v1/workspace", cookieHeader);
  if (response.status === 401) return null;
  if (!response.ok) throw new BackendUnavailableError(response.status);
  return (await response.json()) as Workspace;
}

/** The signed-in user for the given cookies, or null when there is no valid session. */
export async function fetchMe(cookieHeader: string): Promise<Me | null> {
  const response = await get("/api/v1/me", cookieHeader);
  if (response.status === 401) return null;
  if (!response.ok) throw new BackendUnavailableError(response.status);
  return (await response.json()) as Me;
}
