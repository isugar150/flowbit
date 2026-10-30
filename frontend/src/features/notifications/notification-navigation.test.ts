import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { notificationAppUrl } from "./app-link";
import { acknowledgeNotificationLaunch, hasInstalledNotificationApp, shouldTryNotificationApp, tryNotificationApp } from "./notification-navigation";

describe("notification app launch", () => {
  const assign = vi.fn();
  const replace = vi.fn();
  const closeWindow = vi.fn();
  const channels: FakeChannel[] = [];
  class FakeChannel {
    onmessage: ((event: { data: unknown }) => void) | null = null;
    close = vi.fn();
    postMessage = vi.fn();
    constructor() { channels.push(this); }
  }
  let focused = true;
  let hidden = false;
  let doc: EventTarget;

  beforeEach(() => {
    vi.useFakeTimers(); assign.mockReset(); replace.mockReset(); closeWindow.mockReset(); channels.length = 0; focused = true; hidden = false;
    doc = new EventTarget();
    Object.defineProperty(doc, "hidden", { get: () => hidden });
    Object.assign(doc, { hasFocus: () => focused });
    vi.stubGlobal("document", doc);
    vi.stubGlobal("window", { location: { origin: "https://issues.motherk.link", assign, replace }, close: closeWindow, setInterval, clearInterval });
    vi.stubGlobal("BroadcastChannel", FakeChannel);
  });
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("keeps the plus in the protocol URL and falls back once when the app does not open", () => {
    tryNotificationApp("/issue/MK-62");
    expect(assign).toHaveBeenCalledWith(expect.stringMatching(/^web\+flowbit:\/\/issues\.motherk\.link\/issue\/MK-62\?launch=/));
    vi.advanceTimersByTime(5000);
    expect(replace).toHaveBeenCalledExactlyOnceWith("/issue/MK-62");
  });
  it("waits while a protocol picker has focus and falls back when it is dismissed", () => {
    focused = false;
    tryNotificationApp("/issue/MK-62");
    vi.advanceTimersByTime(5000);
    expect(replace).not.toHaveBeenCalled();
    focused = true;
    vi.advanceTimersByTime(2500);
    expect(replace).toHaveBeenCalledOnce();
  });
  it("cancels the fallback when the app takes over and the page becomes hidden", () => {
    tryNotificationApp("/issue/MK-62");
    hidden = true; doc.dispatchEvent(new Event("visibilitychange")); hidden = false;
    vi.advanceTimersByTime(10000);
    expect(replace).not.toHaveBeenCalled();
    expect(closeWindow).not.toHaveBeenCalled();
  });
  it("closes only the requesting tab after a matching app acknowledgement, even after it was hidden", () => {
    const opened = vi.fn();
    tryNotificationApp("/issue/MK-62", opened);
    const launch = new URL(assign.mock.calls[0][0]).searchParams.get("launch");
    const channel = channels[0];
    channel.onmessage?.({ data: { launch: "another-launch" } });
    expect(closeWindow).not.toHaveBeenCalled();
    hidden = true; doc.dispatchEvent(new Event("visibilitychange"));
    channel.onmessage?.({ data: { launch } });
    channel.onmessage?.({ data: { launch } });
    vi.advanceTimersByTime(10000);
    expect(closeWindow).toHaveBeenCalledOnce();
    expect(opened).toHaveBeenCalledOnce();
    expect(replace).not.toHaveBeenCalled();
  });
  it("does not close a tab after the launch was cancelled", () => {
    const cancel = tryNotificationApp("/issue/MK-62");
    const launch = new URL(assign.mock.calls[0][0]).searchParams.get("launch");
    cancel(); channels[0].onmessage?.({ data: { launch } });
    expect(closeWindow).not.toHaveBeenCalled();
  });
  it("acknowledges a validated launch only from a standalone app", () => {
    const launch = "12345678-1234-4234-8234-123456789abc";
    const raw = `web+flowbit://issues.motherk.link/issue/MK-62?launch=${launch}`;
    acknowledgeNotificationLaunch(raw, "https://issues.motherk.link", false);
    acknowledgeNotificationLaunch(raw, "https://other.example", true);
    expect(channels).toHaveLength(0);
    acknowledgeNotificationLaunch(raw, "https://issues.motherk.link", true);
    expect(channels[0].postMessage).toHaveBeenCalledWith({ launch });
    expect(channels[0].close).toHaveBeenCalledOnce();
  });
  it("keeps the confirmed-open state and suppresses web fallback when closing is blocked", () => {
    closeWindow.mockImplementationOnce(() => { throw new Error("close blocked"); });
    const opened = vi.fn();
    tryNotificationApp("/issue/MK-62", opened);
    const launch = new URL(assign.mock.calls[0][0]).searchParams.get("launch");
    expect(() => channels[0].onmessage?.({ data: { launch } })).not.toThrow();
    vi.advanceTimersByTime(5000);
    expect(opened).toHaveBeenCalledOnce();
    expect(replace).not.toHaveBeenCalled();
  });
  it("keeps navigation working when storage policy blocks BroadcastChannel", () => {
    vi.stubGlobal("BroadcastChannel", class { constructor() { throw new Error("storage blocked"); } });
    expect(() => acknowledgeNotificationLaunch("web+flowbit://issues.motherk.link/issue/MK-62?launch=12345678-1234-4234-8234-123456789abc", "https://issues.motherk.link", true)).not.toThrow();
    tryNotificationApp("/issue/MK-62");
    vi.advanceTimersByTime(2500);
    expect(replace).toHaveBeenCalledOnce();
  });
  it("cancels a pending fallback on explicit web navigation or unmount", () => {
    const cancel = tryNotificationApp("/issue/MK-62"); cancel();
    vi.advanceTimersByTime(5000);
    expect(replace).not.toHaveBeenCalled();
  });
  it("uses direct navigation inside the installed app and on other platforms", () => {
    expect(shouldTryNotificationApp(true, "Windows NT Chrome/150")).toBe(false);
    expect(shouldTryNotificationApp(false, "Windows NT Chrome/150")).toBe(true);
    expect(shouldTryNotificationApp(false, "Android Chrome/150")).toBe(false);
    expect(shouldTryNotificationApp(false, "Windows NT Firefox/150")).toBe(false);
    expect(notificationAppUrl("/my-issues", "https://issues.motherk.link")).toBe("web+flowbit://issues.motherk.link/my-issues");
  });
  it("opens the app automatically only when this installation is reported as installed", async () => {
    const browser = { userAgent: "Windows NT Chrome/150", getInstalledRelatedApps: vi.fn().mockResolvedValue([{ platform: "webapp", id: "https://issues.motherk.link/" }]) };
    expect(await hasInstalledNotificationApp(false, browser, "https://issues.motherk.link")).toBe(true);
    browser.getInstalledRelatedApps.mockResolvedValue([]);
    expect(await hasInstalledNotificationApp(false, browser, "https://issues.motherk.link")).toBe(false);
    browser.getInstalledRelatedApps.mockResolvedValue([{ platform: "webapp", id: "https://other.example/" }]);
    expect(await hasInstalledNotificationApp(false, browser, "https://issues.motherk.link")).toBe(false);
  });
  it("uses the web immediately when installation detection is unavailable or fails", async () => {
    expect(await hasInstalledNotificationApp(false, { userAgent: "Windows NT Chrome/150" }, "https://issues.motherk.link")).toBe(false);
    expect(await hasInstalledNotificationApp(false, { userAgent: "Windows NT Chrome/150", getInstalledRelatedApps: async () => { throw new Error("blocked"); } }, "https://issues.motherk.link")).toBe(false);
  });
  it("does not wait indefinitely for installation detection", async () => {
    const result = hasInstalledNotificationApp(false, { userAgent: "Windows NT Chrome/150", getInstalledRelatedApps: () => new Promise(() => {}) }, "https://issues.motherk.link");
    await vi.advanceTimersByTimeAsync(500);
    expect(await result).toBe(false);
  });
});
