"use client";

import { useEffect, useLayoutEffect, useRef } from "react";
import type { Editor } from "@tiptap/react";
import { ExternalLinkIcon, LinkIcon } from "lucide-react";
import { messages } from "@/messages";

const m = messages.editor;

/** The link that was clicked and where its card goes, relative to the editor's box. */
export type LinkCardState = { href: string; anchor: HTMLAnchorElement; left: number; top: number };

/** Opens an address in a new tab; a mail address goes to the mail program instead. */
export function openLink(href: string) {
  if (/^mailto:/i.test(href)) window.location.href = href;
  else window.open(href, "_blank", "noopener,noreferrer");
}

/** The link (not one inside a block of its own, such as an embed's "open" link) that `target` is part of, within the document `dom`. */
export function linkIn(target: EventTarget, dom: HTMLElement | undefined): HTMLAnchorElement | null {
  if (!(target instanceof Element) || !dom) return null;
  const anchor = target.closest("a");
  if (!anchor || !dom.contains(anchor) || anchor.closest("[data-node-view-wrapper]")) return null;
  return anchor;
}

/** A press on a link, remembered until its click: where on the screen it was and which link. */
export type Press = { anchor: HTMLAnchorElement; x: number; y: number };

/** How far the pointer may move between the press and the release for it still to be a click on the link where it was pressed. */
const STILL = 4;

export function pressOn(target: EventTarget, dom: HTMLElement | undefined, x: number, y: number): Press | null {
  const anchor = linkIn(target, dom);
  return anchor ? { anchor, x, y } : null;
}

/**
 * The link a click is on: the one under it, or else the one the press began on when the pointer has not moved. A page can
 * move under a pointer between the press and the release (a table makes room for its toolbar the moment the cursor enters it,
 * so the link slides away); the browser then reports the click on something around it, and the link would never answer.
 */
export function clickedLink(target: EventTarget, dom: HTMLElement | undefined, press: Press | null, x: number, y: number): HTMLAnchorElement | null {
  const under = linkIn(target, dom);
  if (under) return under;
  if (!press || !press.anchor.isConnected) return null;
  return Math.hypot(x - press.x, y - press.y) <= STILL ? press.anchor : null;
}

/** Where a card for `anchor` goes: under the line of it that was clicked (a link can wrap over several lines). */
export function cardPlace(anchor: HTMLAnchorElement, box: HTMLElement, clientY: number): { left: number; top: number } {
  const lines = Array.from(anchor.getClientRects());
  const line = lines.find((rect) => clientY >= rect.top - 1 && clientY <= rect.bottom + 1) ?? lines[0] ?? anchor.getBoundingClientRect();
  const origin = box.getBoundingClientRect();
  return { left: Math.round(line.left - origin.left), top: Math.round(line.bottom - origin.top + 4) };
}

/**
 * What a click on a link shows (D-175): the address it really goes to, and a button to open it. Clicking the link
 * again opens it too. It sits under the link, inside the editor's box, so it moves with the text; it goes away on Esc,
 * on a click elsewhere and when the text changes.
 */
export function LinkCard({ card, editor, onClose }: { card: LinkCardState; editor: Editor; onClose: () => void }) {
  const element = useRef<HTMLDivElement>(null);

  // Not past the right edge of the box it sits in (the editor's).
  useLayoutEffect(() => {
    const node = element.current;
    const box = node?.parentElement;
    if (!node || !box) return;
    const overflow = card.left + node.offsetWidth - box.clientWidth;
    node.style.left = `${Math.max(0, card.left - Math.max(0, overflow))}px`;
  }, [card]);

  useEffect(() => {
    const clickedElsewhere = (event: PointerEvent) => {
      const target = event.target;
      // The link itself is the click handler's to answer: a second click opens it.
      if (target instanceof Node && !element.current?.contains(target) && !card.anchor.contains(target)) onClose();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      // Only the card closes: a dialog around the editor must not take this Escape as its own.
      event.stopPropagation();
      onClose();
    };
    document.addEventListener("pointerdown", clickedElsewhere);
    document.addEventListener("keydown", escape, true);
    editor.on("update", onClose);
    return () => {
      document.removeEventListener("pointerdown", clickedElsewhere);
      document.removeEventListener("keydown", escape, true);
      editor.off("update", onClose);
    };
  }, [card.anchor, editor, onClose]);

  return (
    <div
      ref={element}
      role="group"
      aria-label={m.linkAddress}
      style={{ left: card.left, top: card.top }}
      className="absolute z-20 flex w-max max-w-[min(26rem,100%)] items-center gap-2 rounded-lg bg-popover py-1.5 pr-1.5 pl-2.5 text-popover-foreground shadow-md ring-1 ring-foreground/10"
    >
      <LinkIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <span className="min-w-0 flex-1 text-[0.8125rem] leading-5 break-all select-text">{card.href}</span>
      <a
        href={card.href}
        target="_blank"
        rel="noopener noreferrer"
        // Keep the cursor where it is in the text; the click still goes through.
        onMouseDown={(event) => event.preventDefault()}
        onClick={onClose}
        className="flex h-7 shrink-0 items-center gap-1 rounded-md px-2 text-[0.8125rem] leading-5 font-medium text-primary outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring max-md:h-9 [&>svg]:size-3.5"
      >
        {m.linkOpen}
        <ExternalLinkIcon aria-hidden="true" />
      </a>
    </div>
  );
}
