"use client";

import { useEffect } from "react";
import { messages } from "@/messages";
import { useSendFocus, useViewers } from "./realtime-provider";

const MARK = "data-presence-id";
const SHOWN = "data-presence-by";
/** Moving the pointer across the page passes over many regions; only where it rests is sent. */
const SETTLE_MS = 120;
/** Distinct on both themes with white text on top. */
const COLORS = ["#d9480f", "#0b7285", "#9c36b5", "#2b8a3e", "#c2255c", "#5f3dc4"];

const markOf = (node: EventTarget | null): string | null =>
  node instanceof Element ? (node.closest(`[${MARK}]`)?.getAttribute(MARK) ?? null) : null;

function colorOf(id: string): string {
  let hash = 0;
  for (const char of id) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return COLORS[hash % COLORS.length];
}

/**
 * Shares which marked part of the page (`data-presence-id`) this person is typing in or pointing
 * at, and outlines the parts where the others are, with their names. Where the keyboard focus is
 * wins over where the pointer is. Renders nothing; the look is in globals.css.
 */
export function PresenceFocus() {
  const viewers = useViewers();
  const sendFocus = useSendFocus();

  useEffect(() => {
    let hovered: string | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const report = () => {
      clearTimeout(timer);
      timer = setTimeout(() => sendFocus(markOf(document.activeElement) ?? hovered), SETTLE_MS);
    };
    const onPointer = (event: PointerEvent) => {
      const next = markOf(event.target);
      if (next === hovered) return;
      hovered = next;
      report();
    };
    const onLeave = () => {
      hovered = null;
      report();
    };
    document.addEventListener("pointerover", onPointer);
    document.addEventListener("focusin", report);
    document.addEventListener("focusout", report);
    document.documentElement.addEventListener("pointerleave", onLeave);
    window.addEventListener("blur", onLeave);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("pointerover", onPointer);
      document.removeEventListener("focusin", report);
      document.removeEventListener("focusout", report);
      document.documentElement.removeEventListener("pointerleave", onLeave);
      window.removeEventListener("blur", onLeave);
      sendFocus(null);
    };
  }, [sendFocus]);

  useEffect(() => {
    const byTarget = new Map<string, typeof viewers>();
    for (const viewer of viewers) {
      if (viewer.focus) byTarget.set(viewer.focus, [...(byTarget.get(viewer.focus) ?? []), viewer]);
    }
    const marked: HTMLElement[] = [];
    for (const [target, people] of byTarget) {
      const element = document.querySelector<HTMLElement>(`[${MARK}="${CSS.escape(target)}"]`);
      if (!element) continue;
      const names = people.map((viewer) => viewer.displayName ?? viewer.loginId).join(", ");
      element.setAttribute(SHOWN, names);
      element.setAttribute("title", messages.realtime.pointingHere(names));
      element.style.setProperty("--presence-color", colorOf(people[0].id));
      marked.push(element);
    }
    return () => {
      for (const element of marked) {
        element.removeAttribute(SHOWN);
        element.removeAttribute("title");
        element.style.removeProperty("--presence-color");
      }
    };
  }, [viewers]);

  return null;
}
