const id = "[0-9a-fA-F-]{36}";
const targetPattern = new RegExp(`^/(issue/[A-Za-z][A-Za-z0-9]*-[1-9]\\d*|doc/${id}|project/${id}(/settings)?|my-issues(/created)?)(#access-requests)?$`);

export function isNotificationTarget(path: string): boolean {
  return targetPattern.test(path);
}

export const launchIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function notificationAppUrl(path: string, origin: string): string | null {
  if (!isNotificationTarget(path)) return null;
  const current = new URL(origin);
  return new URL(`web+flowbit://${current.host}${path}`).href;
}

/** Resolve an OS protocol launch to a page on this installation only. */
export function appLinkTarget(raw: string | null, origin: string): string | null {
  if (!raw || !/^web\+flowbit:\/\//i.test(raw) || /[\s\\]/.test(raw)) return null;
  try {
    const link = new URL(raw);
    const current = new URL(origin);
    const targetOrigin = new URL(`${current.protocol}//${link.host}`).origin;
    if (link.protocol !== "web+flowbit:" || targetOrigin !== current.origin) return null;
    if (link.username || link.password) return null;
    if (link.search && (Array.from(link.searchParams.keys()).length !== 1 || !launchIdPattern.test(link.searchParams.get("launch") ?? ""))) return null;
    const path = link.pathname + link.hash;
    return isNotificationTarget(path) ? path : null;
  } catch {
    return null;
  }
}
