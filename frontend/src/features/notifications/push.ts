import { registerPushToken, unregisterPushToken } from "./api";
import type { PushClientConfig } from "./types";

/** Where this browser remembers the token it registered, so it can show "on" and unregister later. */
const STORAGE_KEY = "flowbit.pushToken";
const CHANGED = "flowbit:push-changed";

export class PushError extends Error {
  constructor(readonly reason: "unsupported" | "denied" | "failed") {
    super(reason);
    this.name = "PushError";
  }
}

function storedToken(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function setStoredToken(token: string | null) {
  try {
    if (token) window.localStorage.setItem(STORAGE_KEY, token);
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Without storage the browser simply shows "off" after a reload.
  }
  window.dispatchEvent(new Event(CHANGED));
}

/** For useSyncExternalStore: whether this browser has push switched on. */
export const pushState = {
  subscribe(listener: () => void) {
    window.addEventListener(CHANGED, listener);
    window.addEventListener("storage", listener);
    return () => {
      window.removeEventListener(CHANGED, listener);
      window.removeEventListener("storage", listener);
    };
  },
  isOn: () =>
    storedToken() !== null &&
    typeof Notification !== "undefined" &&
    Notification.permission === "granted",
  isOnServer: () => false,
};

// Firebase is only needed by the few people who switch push on, so it is loaded on demand.
async function messagingFor(config: PushClientConfig) {
  const [{ getApps, initializeApp }, messaging] = await Promise.all([
    import("firebase/app"),
    import("firebase/messaging"),
  ]);
  if (!(await messaging.isSupported())) throw new PushError("unsupported");
  const app =
    getApps()[0] ??
    initializeApp({
      apiKey: config.apiKey,
      projectId: config.projectId,
      messagingSenderId: config.messagingSenderId,
      appId: config.appId,
    });
  return {
    instance: messaging.getMessaging(app),
    getToken: messaging.getToken,
    deleteToken: messaging.deleteToken,
  };
}

/** Asks for permission, registers this browser with Firebase and tells the server its token. */
export async function enablePush(config: PushClientConfig): Promise<void> {
  if (typeof Notification === "undefined" || !("serviceWorker" in navigator))
    throw new PushError("unsupported");
  const messaging = await messagingFor(config);
  if ((await Notification.requestPermission()) !== "granted")
    throw new PushError("denied");
  try {
    const registration = await navigator.serviceWorker.register("/push-sw.js");
    const token = await messaging.getToken(messaging.instance, {
      vapidKey: config.vapidKey,
      serviceWorkerRegistration: registration,
    });
    await registerPushToken(token);
    setStoredToken(token);
  } catch (error) {
    if (error instanceof PushError) throw error;
    throw new PushError("failed");
  }
}

/** Forgets this browser on the server and at Firebase. */
export async function disablePush(
  config: PushClientConfig | null,
): Promise<void> {
  const token = storedToken();
  if (token) await unregisterPushToken(token);
  setStoredToken(null);
  if (!config) return;
  try {
    const messaging = await messagingFor(config);
    await messaging.deleteToken(messaging.instance);
  } catch {
    // The server no longer sends to this browser, which is what matters.
  }
}
