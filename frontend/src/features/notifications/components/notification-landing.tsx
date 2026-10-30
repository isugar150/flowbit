"use client";

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "@/lib/api/client";
import { isNotificationTarget } from "../app-link";
import { shouldTryNotificationApp, tryNotificationApp } from "../notification-navigation";
import { messages } from "@/messages";

/** A protocol launch can be blocked; the web destination always keeps its normal access checks. */
export function NotificationLanding({ token }: { token: string }) {
  const [path, setPath] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [trying, setTrying] = useState(false);
  const cancel = useRef<() => void>(() => {});
  const m = messages.app.notificationLink;

  function openApp(target: string) {
    cancel.current();
    setTrying(true);
    cancel.current = tryNotificationApp(target);
  }

  useEffect(() => {
    let stopped = false;
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) {
      queueMicrotask(() => { if (!stopped) setFailed(true); });
      return () => { stopped = true; };
    }
    apiFetch<{ path: string }>(`/api/v1/public/notification-links/${encodeURIComponent(token)}`)
      .then((result) => {
        if (stopped) return;
        if (!isNotificationTarget(result.path)) { setFailed(true); return; }
        setPath(result.path);
        const standalone = window.matchMedia("(display-mode: standalone)").matches;
        if (!shouldTryNotificationApp(standalone, navigator.userAgent)) window.location.replace(result.path);
        else openApp(result.path);
      })
      .catch(() => { if (!stopped) setFailed(true); });
    return () => { stopped = true; cancel.current(); };
    // Each landing resolves one immutable token; protocol retries are explicit button presses.
  }, [token]);

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background p-6 text-foreground">
      <section className="flex w-full max-w-md flex-col gap-4 rounded-xl border bg-card p-6">
        <h1 className="text-lg font-semibold">{m.title}</h1>
        <p role={failed ? "alert" : "status"} className="text-sm text-muted-foreground">
          {failed ? m.failed : trying ? m.trying : m.loading}
        </p>
        {path ? (
          <div className="flex flex-wrap gap-3 text-sm">
            <button type="button" className="min-h-11 rounded-md bg-primary px-4 text-primary-foreground" onClick={() => openApp(path)}>{m.app}</button>
            <a className="flex min-h-11 items-center rounded-md border px-4" href={path} onClick={() => cancel.current()}>{m.web}</a>
          </div>
        ) : null}
        {failed ? <a href="/my-issues" className="text-sm underline underline-offset-4">{messages.app.protocolLink.home}</a> : null}
        <p className="text-xs text-muted-foreground">{m.hint}</p>
      </section>
    </main>
  );
}
