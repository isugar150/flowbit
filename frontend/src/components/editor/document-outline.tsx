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
/** Room kept between what stays at the top of the scroll box (the page header, the tabs) and the outline, or a heading that was jumped to. */
const GAP = 12;
/** A heading counts as the one being read once it is this far below the stay-put things: the one jumped to (at GAP) is picked out. */
const READING_LINE_BELOW = GAP + 12;
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

/**
 * How much of the top of the scroll box is covered by things that stay there while the page scrolls: the page's header
 * and the tabs under it (a dialog has none). Found by looking at what is there, so the outline and the headings jumped to
 * stay clear of whatever a page puts at its top. `near` is a box in the page's text column, to look at its height.
 */
function coveredTop(scroller: HTMLElement, near: HTMLElement): number {
  const edge = scroller.getBoundingClientRect().top;
  const x = near.getBoundingClientRect().left + 8;
  let covered = 0;
  // One bar under the other (the header, then the tabs): look again under each one found.
  for (let step = 0; step < 4; step++) {
    const bar = document
      .elementsFromPoint(x, edge + covered + 2)
      .find((element) => element !== scroller && scroller.contains(element) && getComputedStyle(element).position === "sticky");
    if (!bar) break;
    covered = Math.max(covered, bar.getBoundingClientRect().bottom - edge);
  }
  return Math.ceil(covered);
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

/** Where the outline stands: how far down the scroll box it stays, and how far down the page it reaches. */
type Place = { inset: number; span: number | null };

/**
 * Measures where the outline goes, again at every scroll and resize: below whatever stays at the top of the scroll box
 * (`inset`), and as far down as the page goes (`span`, the height from the top of the text to the end of what scrolls), so
 * that it keeps following the scroll through what comes after the text (the rest of a ticket, the activity), not only
 * while the text itself is on the screen.
 */
function usePlace(scroller: HTMLElement | null, box: HTMLElement | null): Place {
  const [place, setPlace] = useState<Place>({ inset: 0, span: null });
  useEffect(() => {
    if (!scroller || !box) return;
    const measure = () => {
      const inset = coveredTop(scroller, box);
      const top = box.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop;
      // A little short of the end, so that its own height can never be what makes the page longer.
      const span = Math.max(0, Math.floor(scroller.scrollHeight - top) - 4);
      setPlace((previous) => (previous.inset === inset && previous.span === span ? previous : { inset, span }));
    };
    const timer = setTimeout(measure, DELAY_MS);
    scroller.addEventListener("scroll", measure, { passive: true });
    window.addEventListener("resize", measure);
    const observer = new ResizeObserver(measure);
    observer.observe(scroller);
    observer.observe(box);
    return () => {
      clearTimeout(timer);
      scroller.removeEventListener("scroll", measure);
      window.removeEventListener("resize", measure);
      observer.disconnect();
    };
  }, [scroller, box]);
  return place;
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
 * of `RichTextEditor`), it lays itself in the margin to the right of that box and follows the scroll down the page, below
 * the header and tabs that stay at the top; a heading that is jumped to stops just under them too (D-180).
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
  const entries = useEntries(editor);
  const width = useWidth(scroller);
  const place = usePlace(scroller, container);
  const [active, setActive] = useActive(editor, scroller, entries, place.inset + READING_LINE_BELOW);
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
    const heading = headingsIn(editor)[index];
    if (!heading) return;
    const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
    if (scroller && container) {
      // Under whatever stays at the top as it is now (the tabs may have come or gone), not as it was measured.
      const stop = scroller.getBoundingClientRect().top + coveredTop(scroller, container) + GAP;
      scroller.scrollTo({ top: scroller.scrollTop + heading.getBoundingClientRect().top - stop, behavior });
    } else {
      heading.scrollIntoView({ behavior, block: "start" });
    }
    setActive(index);
    // Where only the marks are shown the list closes when it no longer has the focus.
    if (!full) button.blur();
  };

  return (
    <div
      // As tall as the page goes from the top of the text, so that the outline can follow the scroll all the way down it.
      style={place.span === null ? undefined : { height: place.span }}
      className={cn("pointer-events-none absolute left-full z-20", place.span === null ? "inset-y-0" : "top-0")}
    >
      <nav
        aria-label={m.outline}
        style={{ top: place.inset + GAP }}
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
