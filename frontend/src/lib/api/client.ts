import { ApiError, NetworkError, toApiError } from "./errors";

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  const entry = document.cookie.split("; ").find((part) => part.startsWith(prefix));
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : null;
}

async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(path, { credentials: "same-origin", ...init });
  } catch {
    throw new NetworkError();
  }
}

async function csrfToken(forceRefresh = false): Promise<string | null> {
  if (forceRefresh || !readCookie(CSRF_COOKIE)) {
    await send("/api/v1/auth/csrf", { method: "GET" });
  }
  return readCookie(CSRF_COOKIE);
}

let lastWriteAt = 0;

type DemoBackend = (method: string, path: string, body: unknown) => unknown;
let demoBackend: DemoBackend | null = null;

/**
 * While set, nothing is sent to the server: every call is answered by `backend` instead. The
 * onboarding tour uses it to show real screens with example data that is never saved
 * (features/tour/demo.ts). Pass null to talk to the server again.
 */
export function setDemoBackend(backend: DemoBackend | null) {
  demoBackend = backend;
}

/**
 * When this tab last sent a change. The realtime connection uses it to skip the echo of its own
 * writes (the page already refreshes after a mutation) while other tabs still pick them up.
 */
export function lastLocalWriteAt(): number {
  return lastWriteAt;
}

type Options = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  /** A plain value is sent as JSON. A FormData is sent as multipart, which is how files are uploaded. */
  body?: unknown;
};

/**
 * The single way browser code calls the backend. Adds the CSRF header to state-changing
 * requests, turns problem documents into ApiError, and network failures into NetworkError.
 */
export async function apiFetch<T>(path: string, { method = "GET", body }: Options = {}): Promise<T> {
  if (demoBackend) return demoBackend(method, path, body) as T;
  const request = async (refreshCsrf: boolean): Promise<Response> => {
    const headers = new Headers({ Accept: "application/json" });
    const isForm = body instanceof FormData;
    // For FormData the browser sets Content-Type itself, including the multipart boundary.
    if (body !== undefined && !isForm) headers.set("Content-Type", "application/json");
    if (method !== "GET") {
      const token = await csrfToken(refreshCsrf);
      if (token) headers.set(CSRF_HEADER, token);
    }
    const payload = body === undefined ? undefined : isForm ? body : JSON.stringify(body);
    return send(path, { method, headers, body: payload });
  };

  if (method !== "GET") lastWriteAt = Date.now();
  let response = await request(false);
  if (!response.ok) {
    let error = await toApiError(response);
    // The token cookie can expire or be cleared while a page is open; fetch a new one and retry once.
    if (error.code === "CSRF_TOKEN_INVALID") {
      response = await request(true);
      if (response.ok) return parse<T>(response);
      error = await toApiError(response);
    }
    throw error;
  }
  return parse<T>(response);
}

/** One file as the multipart body the upload endpoints take. */
export function fileForm(file: File): FormData {
  const form = new FormData();
  form.append("file", file);
  return form;
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export { ApiError, NetworkError };
