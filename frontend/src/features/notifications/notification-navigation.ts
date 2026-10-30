import { notificationAppUrl } from "./app-link";

export function shouldTryNotificationApp(standalone: boolean, userAgent: string): boolean {
  return !standalone && /Windows NT/.test(userAgent) && /Chrome\//.test(userAgent);
}

/** Wait while a protocol picker has focus; never redirect a page hidden by an app launch. */
export function tryNotificationApp(path: string): () => void {
  const appUrl = notificationAppUrl(path, window.location.origin);
  if (!appUrl) return () => {};
  const cancel = () => {
    window.clearInterval(timer);
    document.removeEventListener("visibilitychange", visibility);
  };
  const visibility = () => { if (document.hidden) cancel(); };
  const timer = window.setInterval(() => {
    if (!document.hidden && document.hasFocus()) {
      cancel();
      window.location.replace(path);
    }
  }, 2500);
  document.addEventListener("visibilitychange", visibility);
  try { window.location.assign(appUrl); } catch { /* Fall back when no protocol handler exists. */ }
  return cancel;
}
