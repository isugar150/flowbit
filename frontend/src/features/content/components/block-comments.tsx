"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import type { Editor } from "@tiptap/react";
import { MessageSquareIcon, MessageSquarePlusIcon } from "lucide-react";
import type { MentionPerson } from "@/components/editor/mention";
import type { RichDoc } from "@/components/editor/rich-text-editor";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { openThread } from "../api";
import type { ContentTarget, ContentThread } from "../types";
import { useFeedRefresh } from "../use-content-feed";
import { ThreadCard, ThreadComposer } from "./thread-parts";

const m = messages.content.block;

type BlockBox = { id: string; top: number };

/** How long after a change the blocks are measured again (about a frame). */
const MEASURE_DELAY_MS = 16;

/** The top-level blocks of the document that have a number, with how far below the box's top each one starts. */
function measureBlocks(editor: Editor, container: HTMLElement): BlockBox[] {
  const origin = container.getBoundingClientRect();
  const boxes: BlockBox[] = [];
  editor.state.doc.forEach((node, offset) => {
    const id = node.attrs.blockId as string | null | undefined;
    if (!id) return;
    const dom = editor.view.nodeDOM(offset);
    if (!(dom instanceof HTMLElement)) return;
    boxes.push({ id, top: Math.round(dom.getBoundingClientRect().top - origin.top + container.scrollTop) });
  });
  return boxes;
}

const sameBoxes = (a: BlockBox[], b: BlockBox[]) => a.length === b.length && a.every((box, index) => box.id === b[index].id && box.top === b[index].top);

/** Where each block is, kept up to date while the text changes, the page is resized or a picture loads. */
function useBlockBoxes(editor: Editor, container: HTMLElement | null): BlockBox[] {
  const [boxes, setBoxes] = useState<BlockBox[]>([]);
  useEffect(() => {
    if (!container) return;
    // A timer, not requestAnimationFrame: a page in the background gets no frames, and the markers would stay unplaced.
    let timer: ReturnType<typeof setTimeout> | undefined;
    const measure = () => {
      timer = undefined;
      if (editor.isDestroyed) return;
      const next = measureBlocks(editor, container);
      setBoxes((previous) => (sameBoxes(previous, next) ? previous : next));
    };
    const schedule = () => {
      if (timer === undefined) timer = setTimeout(measure, MEASURE_DELAY_MS);
    };
    schedule();
    editor.on("transaction", schedule);
    const observer = new ResizeObserver(schedule);
    observer.observe(editor.view.dom);
    window.addEventListener("resize", schedule);
    return () => {
      if (timer !== undefined) clearTimeout(timer);
      editor.off("transaction", schedule);
      observer.disconnect();
      window.removeEventListener("resize", schedule);
    };
  }, [editor, container]);
  return boxes;
}

/** The number of the top-level block at this height of the editor. */
function blockIdAt(editor: Editor, clientY: number): string | null {
  const { view, state } = editor;
  const content = view.dom.getBoundingClientRect();
  if (clientY < content.top || clientY > content.bottom) return null;
  const hit = view.posAtCoords({ left: content.left + 8, top: clientY });
  if (!hit) return null;
  const resolved = state.doc.resolve(hit.pos);
  const pos = resolved.depth > 0 ? resolved.before(1) : hit.inside >= 0 ? hit.inside : hit.pos;
  return (state.doc.nodeAt(pos)?.attrs.blockId as string | null | undefined) ?? null;
}

/** The number of the block the cursor is in. */
function selectedBlockId(editor: Editor): string | null {
  const { $from } = editor.state.selection;
  if ($from.depth === 0) return null;
  return (editor.state.doc.nodeAt($from.before(1))?.attrs.blockId as string | null | undefined) ?? null;
}

/** The block under the pointer, or the one the cursor is in: where "댓글 달기" shows. */
function useActiveBlock(editor: Editor, container: HTMLElement | null): string | null {
  const [hovered, setHovered] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  useEffect(() => {
    if (!container) return;
    const onMove = (event: MouseEvent) => {
      // While a button is held the pointer is selecting text or dragging a block.
      if (event.buttons !== 0) return;
      // Over a marker the block is already chosen; measuring from there would lose it.
      if (event.target instanceof Element && event.target.closest("[data-block-comment]")) return;
      setHovered(blockIdAt(editor, event.clientY));
    };
    const onLeave = () => setHovered(null);
    const onSelection = () => setSelected(selectedBlockId(editor));
    onSelection();
    container.addEventListener("mousemove", onMove);
    container.addEventListener("mouseleave", onLeave);
    editor.on("selectionUpdate", onSelection);
    return () => {
      container.removeEventListener("mousemove", onMove);
      container.removeEventListener("mouseleave", onLeave);
      editor.off("selectionUpdate", onSelection);
    };
  }, [editor, container]);
  return hovered ?? selected;
}

/** A thread that is still going on, and how many comments are in all of those. */
const openComments = (threads: ContentThread[]) => threads.filter((thread) => !thread.resolved).reduce((sum, thread) => sum + thread.comments.length, 0);

/** The comments of one block: the thread going on (or a box to start one), and the resolved ones folded below. */
function BlockThreads({
  target,
  blockId,
  threads,
  canComment,
  mentionables,
}: {
  target: ContentTarget;
  blockId: string;
  threads: ContentThread[];
  canComment: boolean;
  mentionables: MentionPerson[];
}) {
  const refresh = useFeedRefresh(target);
  const open = useMutation({ mutationFn: (content: RichDoc) => openThread({ target, blockId, content }), onSuccess: refresh });
  const going = threads.filter((thread) => !thread.resolved);
  const resolved = threads.filter((thread) => thread.resolved);

  return (
    <div className="flex max-h-[min(30rem,70dvh)] flex-col gap-2 overflow-y-auto">
      {going.map((thread) => (
        <ThreadCard key={thread.id} target={target} thread={thread} canComment={canComment} mentionables={mentionables} />
      ))}
      {going.length === 0 && canComment ? (
        <ThreadComposer
          label={m.newLabel}
          placeholder={m.newPlaceholder}
          submitLabel={m.submit}
          mentionables={mentionables}
          autoFocus
          onSubmit={(content) => open.mutateAsync(content)}
        />
      ) : null}
      {resolved.length > 0 ? (
        <details className="group">
          <summary className="flex w-fit cursor-pointer list-none items-center gap-1.5 rounded-sm text-[0.8125rem] leading-5 text-muted-foreground outline-none select-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 [&::-webkit-details-marker]:hidden">
            <span aria-hidden="true" className="inline-block transition-transform group-open:rotate-90">
              ›
            </span>
            {m.resolvedThreads(resolved.length)}
          </summary>
          <div className="mt-2 flex flex-col gap-2">
            {resolved.map((thread) => (
              <ThreadCard key={thread.id} target={target} thread={thread} canComment={canComment} mentionables={mentionables} />
            ))}
          </div>
        </details>
      ) : null}
    </div>
  );
}

/**
 * The comments on the blocks of a page, laid over its editor (D-165): a speech bubble at the right of
 * every block that has comments (with how many are still going on), and a faint one for the block
 * under the pointer or the cursor to start one. A bubble opens that block's thread beside it.
 * Anyone who can see the page comments, the view-only role included; on a deleted issue or document
 * the threads can only be read. Threads whose block is gone are not here; the activity lists them.
 */
export function BlockComments({
  editor,
  container,
  target,
  threads,
  canComment,
  mentionables,
}: {
  editor: Editor;
  container: HTMLElement | null;
  target: ContentTarget;
  threads: ContentThread[];
  canComment: boolean;
  mentionables: MentionPerson[];
}) {
  const boxes = useBlockBoxes(editor, container);
  const active = useActiveBlock(editor, container);
  const [openId, setOpenId] = useState<string | null>(null);
  const byBlock = useMemo(() => {
    const groups = new Map<string, ContentThread[]>();
    for (const thread of threads) groups.set(thread.blockId, [...(groups.get(thread.blockId) ?? []), thread]);
    return groups;
  }, [threads]);

  return (
    <>
      {boxes.map((box) => {
        const here = byBlock.get(box.id) ?? [];
        // A block without comments shows its bubble only where one can be started.
        const shown = here.length > 0 || openId === box.id || (canComment && active === box.id);
        if (!shown) return null;
        const going = openComments(here);
        const label = here.length === 0 ? m.add : going > 0 ? m.open(going) : m.openResolved;
        return (
          <Popover key={box.id} open={openId === box.id} onOpenChange={(next) => setOpenId(next ? box.id : null)}>
            <Tooltip>
              <TooltipTrigger
                delay={0}
                render={
                  <PopoverTrigger
                    render={
                      <button
                        type="button"
                        data-block-comment
                        contentEditable={false}
                        aria-label={label}
                        style={{ top: box.top }}
                        className={cn(
                          "absolute right-0 z-10 flex h-6 min-w-6 items-center justify-center gap-0.5 rounded-md px-1 text-xs leading-4 tabular-nums outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                          here.length === 0
                            ? "text-muted-foreground/60 hover:bg-accent hover:text-foreground"
                            : going > 0
                              ? "bg-primary/10 text-primary hover:bg-primary/15"
                              : "text-muted-foreground hover:bg-accent",
                        )}
                      />
                    }
                  />
                }
              >
                {here.length === 0 ? <MessageSquarePlusIcon className="size-3.5" aria-hidden="true" /> : <MessageSquareIcon className="size-3.5" aria-hidden="true" />}
                {going > 0 ? going : null}
              </TooltipTrigger>
              <TooltipContent side="left">{label}</TooltipContent>
            </Tooltip>
            <PopoverContent align="end" className="w-80 max-w-[calc(100vw-1rem)]">
              <BlockThreads target={target} blockId={box.id} threads={here} canComment={canComment} mentionables={mentionables} />
            </PopoverContent>
          </Popover>
        );
      })}
    </>
  );
}
