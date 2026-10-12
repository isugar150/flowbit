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

const EDITOR = ".ProseMirror";
/**
 * A target inside an editor names the block, and the caret when they are typing there:
 * "body#3" is the fourth block of the body, "body#3@17" is after its 17th character.
 */
const EDITOR_TARGET = /^(.*)#(\d+)(?:@(\d+))?$/;

/** The block of an editor (paragraph, heading, list, table…) a node is in, and the editor. */
function blockOf(node: EventTarget | Node | null) {
  const element = node instanceof Element ? node : node instanceof Node ? node.parentElement : null;
  const mark = element?.closest(`[${MARK}]`)?.getAttribute(MARK) ?? null;
  const editor = element?.closest(EDITOR) ?? null;
  let block: Element | null = editor ? (element ?? null) : null;
  while (block && block.parentElement !== editor) block = block.parentElement;
  const index = block && editor ? Array.prototype.indexOf.call(editor.children, block) : -1;
  return { mark, block: index < 0 ? null : block, index };
}

/**
 * The marked part a node is in. Inside an editor the block is added, so others see where in the
 * text this person is, not just that they are in the editor.
 */
function targetOf(node: EventTarget | Node | null): string | null {
  const { mark, block, index } = blockOf(node);
  return mark && block ? `${mark}#${index}` : mark;
}

/** Where the keyboard is: the caret in an editor, otherwise the focused control. */
function focusedTarget(): string | null {
  const active = document.activeElement;
  const selection = document.getSelection();
  const caret = selection?.focusNode ?? null;
  if (active?.matches(EDITOR) && selection && caret && active.contains(caret)) {
    const { mark, block, index } = blockOf(caret);
    if (mark && block) {
      // Counted in characters of the block's text, which is the same on every screen.
      const before = document.createRange();
      before.selectNodeContents(block);
      before.setEnd(caret, selection.focusOffset);
      return `${mark}#${index}@${before.toString().length}`;
    }
  }
  return targetOf(active);
}

/** The line the caret would stand on, after `offset` characters of the block's text. */
function caretRect(block: Element, offset: number): { left: number; top: number; height: number } {
  const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
  let left = offset;
  let last: Text | null = null;
  for (let node = walker.nextNode() as Text | null; node; node = walker.nextNode() as Text | null) {
    if (node.length === 0) continue;
    last = node;
    if (left <= node.length) break;
    left -= node.length;
  }
  if (last) {
    // A collapsed range has no box in some browsers, so measure the character beside the caret.
    const at = Math.min(left, last.length);
    const range = document.createRange();
    range.setStart(last, Math.max(at - 1, 0));
    range.setEnd(last, Math.max(at, 1));
    const rects = range.getClientRects();
    const rect = rects[rects.length - 1];
    if (rect) return { left: at === 0 ? rect.left : rect.right, top: rect.top, height: rect.height };
  }
  // An empty block: the caret stands at its start.
  const rect = block.getBoundingClientRect();
  const line = Number.parseFloat(getComputedStyle(block).lineHeight);
  return { left: rect.left, top: rect.top, height: Number.isFinite(line) ? Math.min(line, rect.height || line) : rect.height };
}

/**
 * Shows where someone else is in an editor: their caret with a name flag when `offset` is given
 * (they are typing), otherwise a frame around the block (they are pointing at it). The editor
 * owns its own DOM and would undo anything set on a block, so this is a separate box laid over
 * it, kept in place as the text reflows. Styled here rather than in the stylesheet, so that the
 * box is always lifted out of the flow.
 */
function markBlock(host: HTMLElement, block: Element, names: string, color: string, offset: number | null): () => void {
  const box = document.createElement("div");
  box.style.position = "absolute";
  box.style.pointerEvents = "none";
  if (offset === null) {
    box.setAttribute(SHOWN, names);
    box.style.setProperty("--presence-color", color);
  } else {
    box.style.width = "2px";
    box.style.background = color;
    box.style.zIndex = "10";
    const flag = document.createElement("span");
    flag.textContent = names;
    Object.assign(flag.style, {
      position: "absolute",
      left: "0",
      bottom: "100%",
      maxWidth: "12rem",
      overflow: "hidden",
      textOverflow: "ellipsis",
      whiteSpace: "nowrap",
      borderRadius: "0.25rem 0.25rem 0.25rem 0",
      background: color,
      padding: "0 0.375rem",
      color: "#fff",
      fontSize: "0.6875rem",
      fontWeight: "500",
      lineHeight: "1rem",
    });
    box.append(flag);
  }
  const hostWasStatic = getComputedStyle(host).position === "static";
  if (hostWasStatic) host.style.position = "relative";
  const place = () => {
    const outer = host.getBoundingClientRect();
    if (offset === null) {
      const inner = block.getBoundingClientRect();
      box.style.top = `${inner.top - outer.top}px`;
      box.style.left = `${inner.left - outer.left}px`;
      box.style.width = `${inner.width}px`;
      box.style.height = `${inner.height}px`;
    } else {
      const caret = caretRect(block, offset);
      box.style.top = `${caret.top - outer.top}px`;
      box.style.left = `${caret.left - outer.left - 1}px`;
      box.style.height = `${caret.height}px`;
    }
  };
  host.append(box);
  place();
  const observer = new ResizeObserver(place);
  observer.observe(host);
  observer.observe(block);
  return () => {
    observer.disconnect();
    box.remove();
    if (hostWasStatic) host.style.position = "";
  };
}

function colorOf(id: string): string {
  let hash = 0;
  for (const char of id) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return COLORS[hash % COLORS.length];
}

/**
 * Shares which marked part of the page (`data-presence-id`) this person is typing in or pointing
 * at, and outlines the parts where the others are, with their names. In an editor that is their
 * caret while they type, or the block they point at, not the whole editor. Where the keyboard focus is
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
      timer = setTimeout(() => sendFocus(focusedTarget() ?? hovered), SETTLE_MS);
    };
    const onPointer = (event: PointerEvent) => {
      const next = targetOf(event.target);
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
    // The caret moving inside an editor.
    document.addEventListener("selectionchange", report);
    document.documentElement.addEventListener("pointerleave", onLeave);
    window.addEventListener("blur", onLeave);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("pointerover", onPointer);
      document.removeEventListener("focusin", report);
      document.removeEventListener("focusout", report);
      document.removeEventListener("selectionchange", report);
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
    const frames: (() => void)[] = [];
    const find = (mark: string) => document.querySelector<HTMLElement>(`[${MARK}="${CSS.escape(mark)}"]`);
    for (const [target, people] of byTarget) {
      const names = people.map((viewer) => viewer.displayName ?? viewer.loginId).join(", ");
      const [, mark, index, offset] = EDITOR_TARGET.exec(target) ?? [];
      const host = mark ? find(mark) : null;
      const block = host?.querySelector(EDITOR)?.children[Number(index)];
      if (host && block) {
        frames.push(markBlock(host, block, names, colorOf(people[0].id), offset === undefined ? null : Number(offset)));
        continue;
      }
      // Not in an editor, or the block is not there (their text is longer than what is saved).
      const element = host ?? find(target);
      if (!element) continue;
      element.setAttribute(SHOWN, names);
      element.setAttribute("title", messages.realtime.pointingHere(names));
      element.style.setProperty("--presence-color", colorOf(people[0].id));
      marked.push(element);
    }
    return () => {
      for (const remove of frames) remove();
      for (const element of marked) {
        element.removeAttribute(SHOWN);
        element.removeAttribute("title");
        element.style.removeProperty("--presence-color");
      }
    };
  }, [viewers]);

  return null;
}
