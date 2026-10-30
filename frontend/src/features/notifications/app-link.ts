/** Resolve an OS protocol launch to a ticket on this installation only. */
export function appLinkTarget(raw: string | null, origin: string): string | null {
  if (!raw || !/^web\+flowbit:\/\//i.test(raw) || /[\s\\]/.test(raw)) return null;
  try {
    const link = new URL(raw);
    const current = new URL(origin);
    const targetOrigin = new URL(`${current.protocol}//${link.host}`).origin;
    if (link.protocol !== "web+flowbit:" || targetOrigin !== current.origin) return null;
    if (link.username || link.password || link.search || link.hash) return null;
    if (!/^\/issue\/[A-Za-z][A-Za-z0-9]*-[1-9]\d*$/.test(link.pathname)) return null;
    return link.pathname;
  } catch {
    return null;
  }
}
