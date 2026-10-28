"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { DndContext, DragOverlay, MouseSensor, useDraggable, useSensor, useSensors, type DragStartEvent } from "@dnd-kit/core";
import { NodeSelection } from "@tiptap/pm/state";
import type { Editor } from "@tiptap/react";
import { GripVerticalIcon } from "lucide-react";
import { dndAccessibility } from "@/lib/dnd";
import { messages } from "@/messages";

/** How far left of a block its handle sits. The handle is this wide, so it touches the text with no gap to cross. */
const HANDLE_OFFSET = 16;
/** The handle stays this long after the pointer leaves the text, time enough to reach it. */
const HIDE_DELAY_MS = 300;
/** The handle is picked up after the mouse has moved this far. */
const DRAG_START_PX = 3;
/** How many characters of the block the ghost under the pointer shows. */
const GHOST_CHARS = 80;

type Target = { pos: number; top: number; left: number };

/** Where a dragged block would land: the position between two top-level blocks, and where the drop line is drawn in the container. */
type DropAt = { insertPos: number; top: number; left: number; width: number };

type Hit = { pos: number; size: number; rect: DOMRect };

/** The top-level block at this height of the editor: where it is in the document and on the screen. */
function blockHit(editor: Editor, clientY: number): Hit | null {
  const { view, state } = editor;
  const content = view.dom.getBoundingClientRect();
  if (clientY < content.top || clientY > content.bottom) return null;
  const hit = view.posAtCoords({ left: content.left + 8, top: clientY });
  if (!hit) return null;
  const resolved = state.doc.resolve(hit.pos);
  const pos = resolved.depth > 0 ? resolved.before(1) : hit.inside >= 0 ? hit.inside : hit.pos;
  const node = state.doc.nodeAt(pos);
  const dom = node ? view.nodeDOM(pos) : null;
  if (!node || !(dom instanceof HTMLElement)) return null;
  return { pos, size: node.nodeSize, rect: dom.getBoundingClientRect() };
}

/** The top-level block at this height of the editor, with where its handle goes in the container. */
function blockAt(editor: Editor, container: HTMLElement, clientY: number): Target | null {
  const hit = blockHit(editor, clientY);
  if (!hit) return null;
  const content = editor.view.dom.getBoundingClientRect();
  const box = container.getBoundingClientRect();
  return {
    pos: hit.pos,
    top: Math.round(hit.rect.top - box.top + container.scrollTop),
    left: Math.round(content.left - box.left - HANDLE_OFFSET),
  };
}

/** Where a block let go at this height would land: before the block under the pointer in its upper half, after it in its lower half. */
function dropAt(editor: Editor, container: HTMLElement, clientY: number): DropAt | null {
  const content = editor.view.dom.getBoundingClientRect();
  // Above or below the text the nearest block is meant: the first, or the last.
  const y = Math.min(Math.max(clientY, content.top + 1), content.bottom - 1);
  const hit = blockHit(editor, y);
  if (!hit) return null;
  const before = y < hit.rect.top + hit.rect.height / 2;
  const box = container.getBoundingClientRect();
  return {
    insertPos: before ? hit.pos : hit.pos + hit.size,
    top: Math.round((before ? hit.rect.top : hit.rect.bottom) - box.top + container.scrollTop),
    left: Math.round(content.left - box.left),
    width: Math.round(content.width),
  };
}

/** The grip itself: what dnd-kit takes hold of. */
function Grip({ target }: { target: Target }) {
  const { setNodeRef, listeners } = useDraggable({ id: "block-handle" });
  return (
    <div
      ref={setNodeRef}
      {...listeners}
      data-block-handle
      contentEditable={false}
      title={messages.editor.dragBlock}
      aria-hidden="true"
      style={{ top: target.top, left: target.left }}
      className="absolute z-10 flex h-[1.375rem] w-4 cursor-grab items-center justify-center rounded text-muted-foreground/70 select-none hover:bg-accent hover:text-foreground active:cursor-grabbing max-md:hidden [&>svg]:size-3.5"
    >
      <GripVerticalIcon />
    </div>
  );
}

/**
 * A grip that appears to the left of the block under the pointer. Dragging it (dnd-kit, D-235) moves the whole block (a paragraph, a
 * list, a table, a picture) to where the drop line shows: the block is taken out and put back between two others in one change of the
 * document, so one undo puts it back. The block keeps what it is, its number for comments included.
 */
export function BlockDragHandle({ editor, container }: { editor: Editor; container: HTMLElement | null }) {
  const [target, setTarget] = useState<Target | null>(null);
  // The block being carried and where it would land: while one is carried the grip stays and the line moves.
  const [carrying, setCarrying] = useState<{ pos: number; ghost: string } | null>(null);
  const [line, setLine] = useState<DropAt | null>(null);
  const landing = useRef<DropAt | null>(null);
  // Whether a block is carried, for the listeners below that outlive a render.
  const carryingRef = useRef(false);
  // Where the pointer is while a block is carried, read from the page: dnd-kit's own delta also counts the scrolling it does itself.
  const pointerY = useRef(0);
  const sensors = useSensors(useSensor(MouseSensor, { activationConstraint: { distance: DRAG_START_PX } }));

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
    let hiding: ReturnType<typeof setTimeout> | undefined;
    const stay = () => clearTimeout(hiding);
    // The grip stays while a block is carried: it is the element dnd-kit holds, and the pointer is then outside the text most of the time.
    const hide = () => {
      clearTimeout(hiding);
      if (!carryingRef.current) setTarget(null);
    };
    // The handle sits at the very edge of the editor, so reaching it can take the pointer outside
    // for a moment; hiding at once made it vanish just as the pointer arrived.
    const hideSoon = () => {
      clearTimeout(hiding);
      hiding = setTimeout(hide, HIDE_DELAY_MS);
    };
    const onMoveInside = (event: MouseEvent) => {
      stay();
      // Over the handle itself the block is already chosen; measuring again from here would lose it.
      if (event.target instanceof Element && event.target.closest("[data-block-handle]")) return;
      onMove(event);
    };
    container.addEventListener("mousemove", onMoveInside);
    container.addEventListener("mouseleave", hideSoon);
    container.addEventListener("keydown", hide);
    return () => {
      clearTimeout(hiding);
      container.removeEventListener("mousemove", onMoveInside);
      container.removeEventListener("mouseleave", hideSoon);
      container.removeEventListener("keydown", hide);
    };
  }, [editor, container]);

  const reset = () => {
    carryingRef.current = false;
    landing.current = null;
    setCarrying(null);
    setLine(null);
  };

  /** Moves the drop line to where a block let go at the pointer would land. */
  const aim = () => {
    if (!container) return;
    const next = dropAt(editor, container, pointerY.current);
    landing.current = next;
    setLine((previous) => (previous?.insertPos === next?.insertPos && previous?.top === next?.top ? previous : next));
  };

  // While a block is carried the line follows the pointer (and the text under it, which moves when the editor scrolls).
  useEffect(() => {
    if (!carrying) return;
    const follow = (event: MouseEvent) => {
      pointerY.current = event.clientY;
      aim();
    };
    window.addEventListener("mousemove", follow);
    return () => window.removeEventListener("mousemove", follow);
    // `aim` is rebuilt every render and reads only refs and the editor.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [carrying, editor, container]);

  const onDragStart = (event: DragStartEvent) => {
    if (!target) return;
    const { state, view } = editor;
    const node = state.doc.nodeAt(target.pos);
    if (!node) return;
    // The block is selected, so that it shows which one is carried.
    view.dispatch(state.tr.setSelection(NodeSelection.create(state.doc, target.pos)));
    const text = node.textContent.trim();
    carryingRef.current = true;
    pointerY.current = (event.activatorEvent as MouseEvent).clientY;
    setCarrying({ pos: target.pos, ghost: text ? text.slice(0, GHOST_CHARS) : messages.editor.dragBlockGhost });
    aim();
  };

  const onDragEnd = () => {
    const from = carrying?.pos;
    const to = landing.current?.insertPos;
    reset();
    setTarget(null);
    if (from === undefined || to === undefined) return;
    const { state, view } = editor;
    const node = state.doc.nodeAt(from);
    // Let go where it was: nothing moves.
    if (!node || to === from || to === from + node.nodeSize) return;
    const tr = state.tr.delete(from, from + node.nodeSize);
    const at = to > from ? to - node.nodeSize : to;
    tr.insert(at, node);
    tr.setSelection(NodeSelection.create(tr.doc, at));
    view.dispatch(tr.scrollIntoView());
  };

  return (
    <DndContext sensors={sensors} autoScroll accessibility={dndAccessibility} onDragStart={onDragStart} onDragMove={aim} onDragEnd={onDragEnd} onDragCancel={reset}>
      {target ? <Grip target={target} /> : null}
      {/* Where the block will land. */}
      {line ? (
        <div
          aria-hidden="true"
          contentEditable={false}
          style={{ top: line.top - 1, left: line.left, width: line.width }}
          className="pointer-events-none absolute z-10 h-0.5 rounded-full bg-primary"
        />
      ) : null}
      {/* The block under the pointer while it is carried: its first words. */}
      {createPortal(
        <DragOverlay dropAnimation={null}>
          {carrying ? <div className="ml-5 w-max max-w-sm truncate rounded-md border bg-popover px-3 py-1.5 text-sm text-popover-foreground shadow-lg">{carrying.ghost}</div> : null}
        </DragOverlay>,
        document.body,
      )}
    </DndContext>
  );
}
