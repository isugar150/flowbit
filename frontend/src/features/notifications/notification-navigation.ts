import { appLinkTarget, launchIdPattern, notificationAppUrl } from "./app-link";

const LAUNCH_CHANNEL = "flowbit-notification-launch";

/** Only an installed app receiving a validated protocol launch can acknowledge it. */
export function acknowledgeNotificationLaunch(raw: string | null, origin: string, standalone: boolean): void {
  if (!raw || !standalone || !appLinkTarget(raw, origin) || typeof BroadcastChannel === "undefined") return;
  const launch = new URL(raw).searchParams.get("launch");
  if (!launch || !launchIdPattern.test(launch)) return;
  try {
    const channel = new BroadcastChannel(LAUNCH_CHANNEL);
    channel.postMessage({ launch });
    channel.close();
  } catch { /* Storage restrictions must not prevent opening the destination. */ }
}

export function shouldTryNotificationApp(standalone: boolean, userAgent: string): boolean {
  return !standalone && /Windows NT/.test(userAgent) && /Chrome\//.test(userAgent);
}

type InstallationNavigator = {
  userAgent: string;
  getInstalledRelatedApps?: () => Promise<Array<{ platform?: string; id?: string; url?: string }>>;
};

/** Only a positive result from this browser profile triggers automatic protocol activation. */
export async function hasInstalledNotificationApp(standalone: boolean, browser: InstallationNavigator, origin: string): Promise<boolean> {
  if (!shouldTryNotificationApp(standalone, browser.userAgent) || !browser.getInstalledRelatedApps) return false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const installed = browser.getInstalledRelatedApps.call(browser).then((apps) => apps.some((app) =>
      app.platform === "webapp" && (app.id === new URL("/", origin).href || app.url === new URL("/manifest.webmanifest", origin).href),
    ));
    return await Promise.race([installed, new Promise<false>((resolve) => { timer = setTimeout(() => resolve(false), 500); })]);
  } catch {
    return false;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/** Wait while a protocol picker has focus; never redirect a page hidden by an app launch. */
export function tryNotificationApp(path: string, onOpened: () => void = () => {}): () => void {
  const appUrl = notificationAppUrl(path, window.location.origin);
  if (!appUrl) return () => {};
  const launch = crypto.randomUUID();
  const url = new URL(appUrl);
  url.searchParams.set("launch", launch);
  let channel: BroadcastChannel | null = null;
  try { if (typeof BroadcastChannel !== "undefined") channel = new BroadcastChannel(LAUNCH_CHANNEL); } catch { /* Keep the web fallback available. */ }
  let active = true;
  const cancel = () => {
    active = false;
    window.clearInterval(timer);
    document.removeEventListener("visibilitychange", visibility);
    channel?.close();
  };
  // A hidden page stops the fallback but still listens for the app's acknowledgement.
  const visibility = () => { if (document.hidden) window.clearInterval(timer); };
  const timer = window.setInterval(() => {
    if (!document.hidden && document.hasFocus()) {
      cancel();
      window.location.replace(path);
    }
  }, 2500);
  if (channel) channel.onmessage = (event: MessageEvent<unknown>) => {
    if (!active || !event.data || typeof event.data !== "object" || !("launch" in event.data) || event.data.launch !== launch) return;
    cancel();
    onOpened();
    try { window.close(); } catch { /* The confirmed-open message remains if closing is blocked. */ }
  };
  document.addEventListener("visibilitychange", visibility);
  try { window.location.assign(url.href); } catch { /* Fall back when no protocol handler exists. */ }
  return cancel;
}
