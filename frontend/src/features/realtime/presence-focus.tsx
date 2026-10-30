"use client";

import { useEffect, useRef } from "react";
import { usePathname } from "next/navigation";
import { personName } from "@/components/person";
import { hasUnsavedEdits, onEditsSaved } from "@/lib/use-autosave";
import { messages } from "@/messages";
import { useSendFocus, useViewers } from "./realtime-provider";

const MARK = "data-presence-id";
const SHOWN = "data-presence-by";
/** A click moves the focus and the caret in quick succession; what is sent is where they end up. */
const SETTLE_MS = 120;
/** Distinct on both themes with white text on top. */
const COLORS = ["#d9480f", "#0b7285", "#9c36b5", "#2b8a3e", "#c2255c", "#5f3dc4"];

const EDITOR = ".ProseMirror";
/**
 * A target inside an editor names the block, and the caret when they are typing there:
 * "body#3" is the fourth block of the body, "body#3@17" is after its 17th character.
 */
const EDITOR_TARGET = /^(.*)#(\d+)(?:@(\d+))?$/;

/** An empty marker stops inheriting a surrounding shared part, such as the activity. */
function excluded(node: Element | null): boolean {
  return node?.closest(`[${MARK}]`)?.getAttribute(MARK) === "";
}

/** The block of an editor (paragraph, heading, list, table…) a node is in, and the editor. */
function blockOf(node: EventTarget | Node | null) {
  const element = node instanceof Element ? node : node instanceof Node ? node.parentElement : null;
  const mark = element?.closest(`[${MARK}]`)?.getAttribute(MARK) || null;
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

/**
 * The line the caret would stand on, after `offset` characters of the block's text. Null when
 * the block has fewer characters: what they typed has not arrived here yet.
 */
function caretRect(block: Element, offset: number): { left: number; top: number; height: number } | null {
  const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
  let left = offset;
  let last: Text | null = null;
  let found = false;
  for (let node = walker.nextNode() as Text | null; node; node = walker.nextNode() as Text | null) {
    if (node.length === 0) continue;
    last = node;
    if (left <= node.length) {
      found = true;
      break;
    }
    left -= node.length;
  }
  if (!found && offset > 0) return null;
  if (last) {
    // A collapsed range has no box in some browsers, so measure the character beside the caret.
    const range = document.createRange();
    range.setStart(last, Math.max(left - 1, 0));
    range.setEnd(last, Math.max(left, 1));
    const rects = range.getClientRects();
    const rect = rects[rects.length - 1];
    if (rect) return { left: left === 0 ? rect.left : rect.right, top: rect.top, height: rect.height };
  }
  // An empty block: the caret stands at its start.
  const rect = block.getBoundingClientRect();
  const line = Number.parseFloat(getComputedStyle(block).lineHeight);
  return { left: rect.left, top: rect.top, height: Number.isFinite(line) ? Math.min(line, rect.height || line) : rect.height };
}

/**
 * Shows where someone else is in an editor: their caret with a name flag when `offset` is given
 * (they are typing), otherwise a frame around the block (they clicked it). The editor
 * owns its own DOM and would undo anything set on a block, so this is a separate box laid over
 * it. It is placed again whenever the text changes, and hidden while the place it points at is
 * not here yet (their text arrives a moment after their caret). Styled here rather than in the
 * stylesheet, so that the box is always lifted out of the flow.
 */
function markEditor(
  host: HTMLElement,
  editor: Element,
  index: number,
  offset: number | null,
  names: string,
  color: string,
): () => void {
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
    const block = editor.children[index];
    const caret = block && offset !== null ? caretRect(block, offset) : null;
    if (!block || (offset !== null && !caret)) {
      box.style.display = "none";
      return;
    }
    box.style.display = "";
    const outer = host.getBoundingClientRect();
    if (caret) {
      box.style.top = `${caret.top - outer.top}px`;
      box.style.left = `${caret.left - outer.left - 1}px`;
      box.style.height = `${caret.height}px`;
    } else {
      const inner = block.getBoundingClientRect();
      box.style.top = `${inner.top - outer.top}px`;
      box.style.left = `${inner.left - outer.left}px`;
      box.style.width = `${inner.width}px`;
      box.style.height = `${inner.height}px`;
    }
  };
  host.append(box);
  place();
  const resized = new ResizeObserver(place);
  resized.observe(host);
  const edited = new MutationObserver(place);
  edited.observe(editor, { childList: true, subtree: true, characterData: true });
  return () => {
    resized.disconnect();
    edited.disconnect();
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
 * Shares which marked part of the page (`data-presence-id`) this person is working in, and outlines
 * the parts where the others are, with their names. A part counts once the person does something
 * in it: clicks it, or types or moves into it with the keyboard. The pointer merely passing over
 * it does not count, or every move of the mouse would light something up on everyone else's
 * screen; nor does focus the page gave by itself (a dialog opening puts it in its first field).
 * In an editor it is the caret while they type, not the whole editor. Renders nothing; the look
 * is in globals.css.
 */
export function PresenceFocus() {
  const viewers = useViewers();
  const sendFocus = useSendFocus();
  // The marked part this person last acted in; clicking outside every marked part clears it.
  const acted = useRef<string | null>(null);

  // Another page (or an issue opened over this one) starts with nothing marked.
  const pathname = usePathname();
  useEffect(() => {
    acted.current = null;
    sendFocus(null);
  }, [pathname, sendFocus]);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const partOf = (target: string) => target.replace(/#.*$/, "");
    const report = () => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        if (excluded(document.activeElement)) {
          acted.current = null;
          sendFocus(null);
          return;
        }
        // Inside the part they acted in, the keyboard says where exactly (the caret in an editor).
        const focused = focusedTarget();
        const target = acted.current && focused && partOf(focused) === partOf(acted.current) ? focused : acted.current;
        // A caret is a place in the text, so it is only true once the text it stands in has been
        // saved: it is sent after the save, and the others get the text and the caret together.
        // Moving the caret without typing has nothing to wait for.
        if (target?.includes("@") && hasUnsavedEdits()) return;
        sendFocus(target);
      }, SETTLE_MS);
    };
    const stopListening = onEditsSaved(report);
    const onPress = (event: PointerEvent) => {
      acted.current = targetOf(event.target);
      report();
    };
    // Typing, or arriving with Tab (the key goes up where the focus landed). Keys pressed in a
    // menu that a part opened leave that part marked: the menu is drawn outside it.
    const onKey = (event: KeyboardEvent) => {
      const target = targetOf(document.activeElement);
      if (target === null && event.key !== "Tab" && !excluded(document.activeElement)) return;
      acted.current = target;
      report();
    };
    const onLeave = () => {
      acted.current = null;
      report();
    };
    document.addEventListener("pointerdown", onPress);
    document.addEventListener("keydown", onKey);
    document.addEventListener("keyup", onKey);
    // The caret moving inside an editor.
    document.addEventListener("selectionchange", report);
    window.addEventListener("blur", onLeave);
    return () => {
      clearTimeout(timer);
      stopListening();
      document.removeEventListener("pointerdown", onPress);
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("keyup", onKey);
      document.removeEventListener("selectionchange", report);
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
      const names = people.map(personName).join(", ");
      const [, mark, index, offset] = EDITOR_TARGET.exec(target) ?? [];
      const host = mark ? find(mark) : null;
      const editor = host?.querySelector(EDITOR);
      if (host && editor) {
        // A nested comment editor does not share the surrounding activity's caret.
        if (excluded(editor)) continue;
        const caret = offset === undefined ? null : Number(offset);
        frames.push(markEditor(host, editor, Number(index), caret, names, colorOf(people[0].id)));
        continue;
      }
      // Not in an editor.
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
