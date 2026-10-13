"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { lastLocalWriteAt } from "@/lib/api/client";
import { PresenceFocus } from "./presence-focus";

/** Someone with the same page open (GET /api/v1/realtime, "presence"). */
export type Viewer = {
  id: string;
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
  /** The `data-presence-id` of the part of the page they are pointing at or typing in, if any. */
  focus: string | null;
};

/** What one part of the page wants to hear about. See RealtimeScope. */
type Scope = {
  topics: string[];
  /** The page this part stands for, for presence. The scope with the highest priority wins. */
  presence?: string;
  priority: number;
};

type ServerMessage =
  | { type: "changed"; topic: string; actorId: string }
  | { type: "presence"; key: string; viewers: Viewer[] }
  | { type: "ping" };

type RealtimeContextValue = {
  register: (scope: Scope) => () => void;
  /** Others on the current page; the signed-in user is left out. */
  viewers: Viewer[];
  /** The page this tab stands on for presence (`project:<id>`, `issue:<number>`); null on a page that has none. */
  pageKey: string | null;
  /** The signed-in user. */
  userId: string;
  /** Tells the others which part of the page this person is on (null: none). */
  sendFocus: (target: string | null) => void;
};

const RealtimeContext = createContext<RealtimeContextValue | null>(null);

/** Echoes of this tab's own writes arrive within this window and are skipped. */
const OWN_WRITE_WINDOW_MS = 3000;
/** Several changes in a row (autosave while typing) cause one refresh. */
const REFRESH_DELAY_MS = 250;
/** The server closes with this code when the session has ended; reconnecting would not help. */
const SESSION_ENDED = 4401;

/**
 * One WebSocket per tab to /api/v1/realtime (docs/02-architecture.md "실시간 동기화").
 * When something the page shows changes, the server components are rendered again
 * (router.refresh), the same way a page updates after its own mutation. It also tells who else
 * has the same page open. If the connection drops it reconnects with backoff and refreshes once,
 * since changes may have been missed; the app keeps working without it.
 */
export function RealtimeProvider({ userId, children }: { userId: string; children: React.ReactNode }) {
  const router = useRouter();
  const scopes = useRef(new Map<number, Scope>());
  const nextScopeId = useRef(0);
  const socket = useRef<WebSocket | null>(null);
  const presenceKey = useRef<string | null>(null);
  const focusTarget = useRef<string | null>(null);
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [viewers, setViewers] = useState<Viewer[]>([]);
  const [pageKey, setPageKey] = useState<string | null>(null);

  const scheduleRefresh = useCallback(() => {
    if (refreshTimer.current) return;
    refreshTimer.current = setTimeout(() => {
      refreshTimer.current = null;
      router.refresh();
    }, REFRESH_DELAY_MS);
  }, [router]);

  const sendSubscription = useCallback(() => {
    const all = [...scopes.current.values()];
    const topics = [...new Set(all.flatMap((scope) => scope.topics))];
    const presence =
      all.filter((scope) => scope.presence).sort((a, b) => b.priority - a.priority)[0]?.presence ?? null;
    if (presence !== presenceKey.current) {
      presenceKey.current = presence;
      setViewers([]);
      setPageKey(presence);
    }
    const ws = socket.current;
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "subscribe", topics, presence }));
      // A subscription starts with no focus on the server; say again where this person is.
      if (focusTarget.current) ws.send(JSON.stringify({ type: "focus", target: focusTarget.current }));
    }
  }, []);

  const sendFocus = useCallback((target: string | null) => {
    if (target === focusTarget.current) return;
    focusTarget.current = target;
    const ws = socket.current;
    if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "focus", target }));
  }, []);

  useEffect(() => {
    let stopped = false;
    let attempt = 0;
    let connectedBefore = false;
    let retry: ReturnType<typeof setTimeout> | undefined;

    const connect = () => {
      const scheme = window.location.protocol === "https:" ? "wss" : "ws";
      const ws = new WebSocket(`${scheme}://${window.location.host}/api/v1/realtime`);
      socket.current = ws;
      ws.onopen = () => {
        attempt = 0;
        sendSubscription();
        if (connectedBefore) scheduleRefresh();
        connectedBefore = true;
      };
      ws.onmessage = (event) => {
        let message: ServerMessage;
        try {
          message = JSON.parse(String(event.data)) as ServerMessage;
        } catch {
          return;
        }
        if (message.type === "changed") {
          const ownEcho = message.actorId === userId && Date.now() - lastLocalWriteAt() < OWN_WRITE_WINDOW_MS;
          if (!ownEcho) scheduleRefresh();
        } else if (message.type === "presence" && message.key === presenceKey.current) {
          setViewers(message.viewers);
        }
      };
      ws.onclose = (event) => {
        if (socket.current === ws) socket.current = null;
        setViewers([]);
        if (stopped || event.code === SESSION_ENDED) return;
        // 1s, 2s, 4s … up to 30s, spread out so tabs do not all come back at once.
        const delay = Math.min(30_000, 1000 * 2 ** attempt) * (0.75 + Math.random() / 2);
        attempt += 1;
        retry = setTimeout(connect, delay);
      };
    };

    connect();
    return () => {
      stopped = true;
      clearTimeout(retry);
      if (refreshTimer.current) clearTimeout(refreshTimer.current);
      socket.current?.close();
      socket.current = null;
    };
  }, [userId, sendSubscription, scheduleRefresh]);

  const register = useCallback(
    (scope: Scope) => {
      const id = nextScopeId.current++;
      scopes.current.set(id, scope);
      sendSubscription();
      return () => {
        scopes.current.delete(id);
        sendSubscription();
      };
    },
    [sendSubscription],
  );

  const value = useMemo(
    () => ({ register, sendFocus, viewers: viewers.filter((viewer) => viewer.id !== userId), pageKey, userId }),
    [register, sendFocus, viewers, pageKey, userId],
  );
  return (
    <RealtimeContext.Provider value={value}>
      {children}
      <PresenceFocus />
    </RealtimeContext.Provider>
  );
}

/**
 * Subscribes the page to change notices for `topics` (such as `project:FLOW`, `issue:FLOW-12`)
 * and, with `presence`, marks which page the person is on. Renders nothing.
 */
export function RealtimeScope({
  topics,
  presence,
  priority = 0,
}: {
  topics: string[];
  presence?: string;
  /** A more specific page (an issue inside a project) has a higher priority for presence. */
  priority?: number;
}) {
  const context = useContext(RealtimeContext);
  const register = context?.register;
  const topicList = topics.join(" ");
  useEffect(() => {
    if (!register) return;
    return register({ topics: topicList.split(" ").filter(Boolean), presence, priority });
  }, [register, topicList, presence, priority]);
  return null;
}

export function useViewers(): Viewer[] {
  return useContext(RealtimeContext)?.viewers ?? [];
}

/** The page the avatars speak for, and who is asking; see PresenceAvatars. */
export function usePresencePage(): { pageKey: string | null; userId: string | null } {
  const context = useContext(RealtimeContext);
  return { pageKey: context?.pageKey ?? null, userId: context?.userId ?? null };
}

export function useSendFocus(): (target: string | null) => void {
  const send = useContext(RealtimeContext)?.sendFocus;
  return useCallback((target: string | null) => send?.(target), [send]);
}
