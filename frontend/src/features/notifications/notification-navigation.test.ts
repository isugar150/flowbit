import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { notificationAppUrl } from "./app-link";
import { shouldTryNotificationApp, tryNotificationApp } from "./notification-navigation";

describe("notification app launch", () => {
  const assign = vi.fn();
  const replace = vi.fn();
  let focused = true;
  let hidden = false;
  let doc: EventTarget;

  beforeEach(() => {
    vi.useFakeTimers(); assign.mockReset(); replace.mockReset(); focused = true; hidden = false;
    doc = new EventTarget();
    Object.defineProperty(doc, "hidden", { get: () => hidden });
    Object.assign(doc, { hasFocus: () => focused });
    vi.stubGlobal("document", doc);
    vi.stubGlobal("window", { location: { origin: "https://issues.motherk.link", assign, replace }, setInterval, clearInterval });
  });
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("keeps the plus in the protocol URL and falls back once when the app does not open", () => {
    tryNotificationApp("/issue/MK-62");
    expect(assign).toHaveBeenCalledWith("web+flowbit://issues.motherk.link/issue/MK-62");
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
});
