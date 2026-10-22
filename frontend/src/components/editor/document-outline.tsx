"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { Editor } from "@tiptap/react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.editor;

type Entry = { level: number; text: string };

/** The width of the page (its scroll box) from which the whole outline stands beside the document. */
const FULL_FROM = 1280;
/** Narrower than that only a mark per heading is shown, down to this width; below it nothing (a phone has no room beside the text). */
const MARKS_FROM = 640;
/** How far below the top of the scroll box the outline stays: under the page's header (a page) or a little off the edge (a dialog, whose bar is outside its scroll box). */
const STICKY_TOP_PAGE = 56;
const STICKY_TOP_DIALOG = 16;
/**
 * A heading counts as the one being read once it has reached this far below the top of the scroll box. Past where a
 * heading stops when it is jumped to (64px, `scroll-margin-top` in globals.css), so the heading that was chosen is the one picked out.
 */
const READING_LINE_PAGE = 96;
const READING_LINE_DIALOG = 72;
/** About a frame; a timer, not requestAnimationFrame, because a page in the background gets no frames. */
const DELAY_MS = 16;

/** Indentation of an entry by the level of its heading (1, 2, 3 and deeper). */
const INDENT = ["pl-2", "pl-5", "pl-8"];
/** Length of the mark of a heading by its level; the longest fits the 16px the marks have (a dialog's padding is 20px). */
const MARK_WIDTH = ["w-3.5", "w-2.5", "w-1.5"];

/** The box that scrolls the page the editor is on: the app's <main>, or the scroll box of a dialog. */
function scrollParentOf(element: HTMLElement | null): HTMLElement | null {
  for (let node = element?.parentElement; node; node = node.parentElement) {
    const { overflowY } = getComputedStyle(node);
    if (overflowY === "auto" || overflowY === "scroll") return node;
  }
  return null;
}

/** The headings of the document that have text, in order. They are read from the page, where they are also scrolled to. */
function headingsIn(editor: Editor): HTMLElement[] {
  if (editor.isDestroyed) return [];
  return Array.from(editor.view.dom.querySelectorAll<HTMLElement>("h1, h2, h3, h4, h5, h6")).filter((heading) => (heading.textContent ?? "").trim() !== "");
}

const sameEntries = (a: Entry[], b: Entry[]) => a.length === b.length && a.every((entry, index) => entry.level === b[index].level && entry.text === b[index].text);

/** The headings as they are now, kept up to date while the text is typed, replaced or changed by someone else. */
function useEntries(editor: Editor): Entry[] {
  const [entries, setEntries] = useState<Entry[]>([]);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const read = () => {
      timer = undefined;
      const next = headingsIn(editor).map((heading) => ({ level: Number(heading.tagName.slice(1)), text: (heading.textContent ?? "").trim() }));
      setEntries((previous) => (sameEntries(previous, next) ? previous : next));
    };
    const schedule = () => {
      if (timer === undefined) timer = setTimeout(read, DELAY_MS);
    };
    schedule();
    editor.on("transaction", schedule);
    return () => {
      if (timer !== undefined) clearTimeout(timer);
      editor.off("transaction", schedule);
    };
  }, [editor]);
  return entries;
}

/** The width of the box the page scrolls in, kept current while the window or the sidebar changes it. */
function useWidth(scroller: HTMLElement | null): number {
  const subscribe = useCallback(
    (notify: () => void) => {
      if (!scroller) return () => {};
      const observer = new ResizeObserver(notify);
      observer.observe(scroller);
      return () => observer.disconnect();
    },
    [scroller],
  );
  return useSyncExternalStore(
    subscribe,
    () => scroller?.clientWidth ?? 0,
    () => 0,
  );
}

/** Which heading is being read: the last one that has reached the reading line, or the last one at the end of the page. */
function useActive(editor: Editor, scroller: HTMLElement | null, entries: Entry[], readingLine: number): [number, (index: number) => void] {
  const [active, setActive] = useState(0);
  useEffect(() => {
    if (!scroller) return;
    const update = () => {
      const headings = headingsIn(editor);
      if (headings.length === 0) return;
      const line = scroller.getBoundingClientRect().top + readingLine;
      let current = 0;
      headings.forEach((heading, index) => {
        if (heading.getBoundingClientRect().top <= line) current = index;
      });
      // A last heading too close to the end of the page ever to reach the line is still the one at the bottom.
      if (scroller.scrollTop > 0 && scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 2) current = headings.length - 1;
      setActive(current);
    };
    const timer = setTimeout(update, DELAY_MS);
    scroller.addEventListener("scroll", update, { passive: true });
    return () => {
      clearTimeout(timer);
      scroller.removeEventListener("scroll", update);
    };
  }, [editor, scroller, entries, readingLine]);
  return [active, setActive];
}

/**
 * The headings of a document listed beside it, to jump to them (D-170). Wide pages show the list itself, like Notion's;
 * narrower ones show a mark for each heading at the edge of the text and open the list when the pointer (or focus) is
 * on them; phones show nothing. The one being read is picked out. Given the editor and the box it sits in (the overlay
 * of `RichTextEditor`), it lays itself in the margin to the right of that box and stays in view while the page scrolls.
 */
export function DocumentOutline({
  editor,
  container,
  compact = false,
}: {
  editor: Editor;
  container: HTMLElement | null;
  /**
   * Only the marks, however wide the page: for a page with something else beside the text, where the whole list would lie
   * over it (the project overview, whose cards stand right of the description; a ticket and a milestone, whose panel stands
   * right of the text; D-177, D-179). The list opens over the text.
   */
  compact?: boolean;
}) {
  // The page scrolls in the <main> of the app's frame (a ticket or a milestone, in the scroll box of its dialog), and the
  // width left beside the text is what that box has to spare.
  const scroller = useMemo(() => scrollParentOf(container), [container]);
  const onPage = scroller?.tagName === "MAIN";
  const stickyTop = onPage ? STICKY_TOP_PAGE : STICKY_TOP_DIALOG;
  const entries = useEntries(editor);
  const width = useWidth(scroller);
  const [active, setActive] = useActive(editor, scroller, entries, onPage ? READING_LINE_PAGE : READING_LINE_DIALOG);
  const list = useRef<HTMLOListElement>(null);
  const full = !compact && width >= FULL_FROM;

  // A long list scrolls by itself to keep the heading being read in view.
  useEffect(() => {
    const box = list.current;
    const item = box?.children[active];
    if (!box || !(item instanceof HTMLElement) || box.clientHeight === 0) return;
    if (item.offsetTop < box.scrollTop) box.scrollTop = item.offsetTop;
    else if (item.offsetTop + item.offsetHeight > box.scrollTop + box.clientHeight) box.scrollTop = item.offsetTop + item.offsetHeight - box.clientHeight;
  }, [active, entries, full]);

  if (entries.length === 0 || width < MARKS_FROM) return null;

  const go = (index: number, button: HTMLElement) => {
    headingsIn(editor)[index]?.scrollIntoView({
      behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
      block: "start",
    });
    setActive(index);
    // Where only the marks are shown the list closes when it no longer has the focus.
    if (!full) button.blur();
  };

  return (
    <div className="pointer-events-none absolute inset-y-0 left-full z-20">
      <nav
        aria-label={m.outline}
        style={{ top: stickyTop }}
        className={cn("group/outline pointer-events-auto sticky", full ? "ml-6 w-56" : "ml-0.5 w-4")}
      >
        {full ? null : (
          <button
            type="button"
            aria-label={m.outlineOpen}
            className="flex max-h-[calc(100dvh-9rem)] w-4 flex-col items-end gap-2 overflow-hidden rounded-md py-2 outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            {entries.map((entry, index) => (
              <span
                key={index}
                aria-hidden="true"
                className={cn("h-0.5 shrink-0 rounded-full", MARK_WIDTH[Math.min(entry.level, 3) - 1], index === active ? "bg-foreground" : "bg-muted-foreground/40")}
              />
            ))}
          </button>
        )}
        <ol
          ref={list}
          className={cn(
            "max-h-[min(28rem,calc(100dvh-9rem))] overflow-y-auto overscroll-contain rounded-xl border bg-popover p-1.5 text-popover-foreground",
            // Beside the text it is always there; at the edge it opens over the text while the pointer or focus is on the marks.
            full ? "relative" : "absolute top-0 right-0 hidden w-56 shadow-md group-focus-within/outline:block group-hover/outline:block",
          )}
        >
          {entries.map((entry, index) => (
            <li key={index}>
              <button
                type="button"
                aria-current={index === active ? "location" : undefined}
                onClick={(event) => go(index, event.currentTarget)}
                className={cn(
                  "line-clamp-2 w-full rounded-md py-1 pr-2 text-left text-[0.8125rem] leading-5 outline-none hover:bg-accent hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50",
                  INDENT[Math.min(entry.level, 3) - 1],
                  index === active ? "text-primary" : "text-muted-foreground",
                )}
              >
                {entry.text}
              </button>
            </li>
          ))}
        </ol>
      </nav>
    </div>
  );
}
