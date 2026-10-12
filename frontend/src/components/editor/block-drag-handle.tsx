"use client";

import { useEffect, useState } from "react";
import { NodeSelection } from "@tiptap/pm/state";
import type { EditorView } from "@tiptap/pm/view";
import type { Editor } from "@tiptap/react";
import { GripVerticalIcon } from "lucide-react";
import { messages } from "@/messages";

/** How far left of a block its handle sits, and how wide the handle is. */
const HANDLE_OFFSET = 16;

type Target = { pos: number; top: number; left: number };

/** What the editor is dragging; it reads this when the drop happens. */
function setDragging(view: EditorView, dragging: EditorView["dragging"]) {
  view.dragging = dragging;
}

/** The top-level block at this height of the editor, with where its handle goes in the container. */
function blockAt(editor: Editor, container: HTMLElement, clientY: number): Target | null {
  const { view, state } = editor;
  const content = view.dom.getBoundingClientRect();
  if (clientY < content.top || clientY > content.bottom) return null;
  const hit = view.posAtCoords({ left: content.left + 8, top: clientY });
  if (!hit) return null;
  const resolved = state.doc.resolve(hit.pos);
  const pos = resolved.depth > 0 ? resolved.before(1) : hit.inside >= 0 ? hit.inside : hit.pos;
  const dom = state.doc.nodeAt(pos) ? view.nodeDOM(pos) : null;
  if (!(dom instanceof HTMLElement)) return null;
  const block = dom.getBoundingClientRect();
  const box = container.getBoundingClientRect();
  return {
    pos,
    top: Math.round(block.top - box.top + container.scrollTop),
    left: Math.round(content.left - box.left - HANDLE_OFFSET),
  };
}

/**
 * A grip that appears to the left of the block under the pointer. Dragging it moves the whole
 * block (a paragraph, a list, a table, a picture) to where the drop line shows.
 */
export function BlockDragHandle({ editor, container }: { editor: Editor; container: HTMLElement | null }) {
  const [target, setTarget] = useState<Target | null>(null);

  useEffect(() => {
    if (!container) return;
    const onMove = (event: MouseEvent) => {
      // While a button is held the pointer is selecting text or already dragging.
      if (event.buttons !== 0) return;
      const next = blockAt(editor, container, event.clientY);
      setTarget((previous) =>
        previous?.pos === next?.pos && previous?.top === next?.top && previous?.left === next?.left ? previous : next,
      );
    };
    const hide = () => setTarget(null);
    container.addEventListener("mousemove", onMove);
    container.addEventListener("mouseleave", hide);
    container.addEventListener("keydown", hide);
    return () => {
      container.removeEventListener("mousemove", onMove);
      container.removeEventListener("mouseleave", hide);
      container.removeEventListener("keydown", hide);
    };
  }, [editor, container]);

  if (!target) return null;

  return (
    <div
      draggable
      contentEditable={false}
      title={messages.editor.dragBlock}
      aria-hidden="true"
      style={{ top: target.top, left: target.left }}
      className="absolute z-10 flex h-[1.375rem] w-3.5 cursor-grab items-center justify-center rounded text-muted-foreground/70 hover:bg-accent hover:text-foreground active:cursor-grabbing max-md:hidden [&>svg]:size-3.5"
      onDragStart={(event) => {
        const { view, state } = editor;
        if (!state.doc.nodeAt(target.pos)) return event.preventDefault();
        // Select the block and hand it to the editor as a move: its own drop handling does the rest.
        const selection = NodeSelection.create(state.doc, target.pos);
        view.dispatch(state.tr.setSelection(selection));
        setDragging(view, { slice: selection.content(), move: true });
        event.dataTransfer.effectAllowed = "move";
        event.dataTransfer.setData("text/plain", selection.node.textContent || " ");
        const dom = view.nodeDOM(target.pos);
        if (dom instanceof HTMLElement) event.dataTransfer.setDragImage(dom, 0, 0);
      }}
      onDragEnd={() => {
        // Dropped outside the document: nothing moved, so forget the drag.
        setDragging(editor.view, null);
        setTarget(null);
      }}
    >
      <GripVerticalIcon />
    </div>
  );
}
