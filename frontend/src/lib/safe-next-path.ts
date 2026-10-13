/** The first screen after signing in: one's own tickets (docs/01-product.md section 3). */
const FALLBACK = "/my-issues";

/**
 * Where to send someone after sign-in. Only paths inside this site are allowed, so a crafted
 * `?next=` can never bounce a user to another origin.
 */
export function safeNextPath(next: string | null | undefined): string {
  if (!next || !next.startsWith("/")) return FALLBACK;
  // "//host" and "/\host" are treated by browsers as another origin.
  if (next.startsWith("//") || next.startsWith("/\\")) return FALLBACK;
  if (/[\u0000-\u001f]/.test(next)) return FALLBACK;
  try {
    const url = new URL(next, "http://flowbit.invalid");
    if (url.origin !== "http://flowbit.invalid") return FALLBACK;
    const path = url.pathname + url.search + url.hash;
    return path.startsWith("/login") || path.startsWith("/setup") ? FALLBACK : path;
  } catch {
    return FALLBACK;
  }
}
