"use client";

import Image from "@tiptap/extension-image";
import { NodeViewWrapper, ReactNodeViewRenderer, useEditorState, type NodeViewProps } from "@tiptap/react";
import { useEffect, useRef, useState } from "react";
import { MessageSquareTextIcon, Grid2X2Icon, UngroupIcon, TextAlignStartIcon, TextAlignCenterIcon, TextAlignEndIcon } from "lucide-react";
import { messages } from "@/messages";
import { newBlockId } from "./block-id";
import { DEFAULT_GALLERY_HEIGHT, imageGalleryPlugin, imageRun, limitGalleryHeight, ungroupedImagesFrom } from "./image-layout";

function ImageNode({ node, editor, updateAttributes, selected, getPos }: NodeViewProps) {
  const imageRef = useRef<HTMLImageElement>(null);
  const drag = useRef<{ x: number; width: number; next: number } | null>(null);
  const galleryDrag = useRef<{ y: number; height: number; next: number; frames: HTMLElement[] } | null>(null);
  const [preview, setPreview] = useState<number | null>(null);
  const [editingCaption, setEditingCaption] = useState(false);
  const [choosingGroup, setChoosingGroup] = useState(false);
  const groupSelect = useRef<HTMLSelectElement>(null);
  useEffect(() => { if (choosingGroup) groupSelect.current?.focus(); }, [choosingGroup]);
  const captionRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => { if (editingCaption) captionRef.current?.focus(); }, [editingCaption]);
  const [editable, setEditable] = useState(editor.isEditable);
  useEffect(() => {
    const sync = () => setEditable(editor.isEditable);
    editor.on("update", sync);
    return () => { editor.off("update", sync); };
  }, [editor]);
  const storedWidth = typeof node.attrs.width === "number" && node.attrs.width > 0 ? node.attrs.width : undefined;
  const { grouped, available } = useEditorState({ editor, selector: () => {
    const pos = getPos();
    const doc = editor.state.doc;
    if (pos === undefined || pos >= doc.content.size) return { grouped: false, available: 0 };
    const group = doc.nodeAt(pos)?.attrs.imageGroup;
    const grouped = Boolean(group && imageRun(doc, pos, group).length > 1);
    return { grouped, available: grouped ? 0 : ungroupedImagesFrom(doc, pos).length };
  } });
  const m = messages.editor;

  function arrange(count?: number) {
    const pos = getPos();
    if (!editor.isEditable || pos === undefined) return;
    const doc = editor.state.doc;
    const group = doc.nodeAt(pos)?.attrs.imageGroup;
    const run = count === undefined
      ? (group ? imageRun(doc, pos, group) : [])
      : ungroupedImagesFrom(doc, pos).slice(0, count);
    if (run.length < 2 || (count !== undefined && run.length !== count)) return;
    const imageGroup = count === undefined ? null : newBlockId();
    const transaction = editor.state.tr;
    run.forEach((image) => transaction.setNodeMarkup(image.pos, undefined, { ...image.node.attrs, imageGroup, galleryHeight: null }));
    editor.view.dispatch(transaction);
  }

  function currentGallery() {
    const pos = getPos();
    if (pos === undefined) return [];
    const doc = editor.state.doc;
    const group = doc.nodeAt(pos)?.attrs.imageGroup;
    return group ? imageRun(doc, pos, group) : [];
  }

  function commitGalleryHeight(height: number) {
    const run = currentGallery();
    if (!editor.isEditable || run.length < 2) return;
    const transaction = editor.state.tr;
    const galleryHeight = limitGalleryHeight(height);
    run.forEach(({ pos, node }) => transaction.setNodeMarkup(pos, undefined, { ...node.attrs, galleryHeight }));
    editor.view.dispatch(transaction);
  }

  function clearGalleryPreview() {
    galleryDrag.current?.frames.forEach((frame) => frame.style.removeProperty("--gallery-preview-height"));
    galleryDrag.current = null;
  }

  function limit(width: number) {
    const available = imageRef.current?.closest<HTMLElement>(".tiptap")?.clientWidth ?? 1600;
    return Math.max(1, Math.min(available, Math.max(32, Math.round(width))));
  }

  function commit(width: number) {
    if (editor.isEditable) updateAttributes({ width: limit(width), height: null });
  }

  return (
    <NodeViewWrapper className="editor-image" data-align={node.attrs.align ?? "left"} data-in-gallery={grouped || undefined} data-block-id={node.attrs.blockId ?? undefined} contentEditable={false}>
      {editable && (
        <div className="editor-image-tools" role="group" aria-label={m.imageLayout} onClick={(event) => event.stopPropagation()}>
          {!grouped && ([
            ["left", m.imageAlignLeft, TextAlignStartIcon],
            ["center", m.imageAlignCenter, TextAlignCenterIcon],
            ["right", m.imageAlignRight, TextAlignEndIcon],
          ] as const).map(([align, label, Icon]) => (
            <button key={align} type="button" aria-label={label} title={label} aria-pressed={(node.attrs.align ?? "left") === align}
              onMouseDown={(event) => event.preventDefault()} onClick={() => { if (editor.isEditable) updateAttributes({ align }); }}>
              <Icon size={16} aria-hidden="true" />
            </button>
          ))}
          <button type="button" aria-label={m.imageCaption} title={m.imageCaption}
            onMouseDown={(event) => event.preventDefault()} onClick={() => setEditingCaption(true)}>
            <MessageSquareTextIcon size={16} aria-hidden="true" />
          </button>
          <button type="button" aria-label={grouped ? m.imageUngroup : m.imageAutoLayout} title={grouped ? m.imageUngroup : m.imageAutoLayout}
            disabled={!grouped && available < 2} aria-expanded={grouped ? undefined : choosingGroup}
            onMouseDown={(event) => event.preventDefault()} onClick={() => grouped ? arrange() : setChoosingGroup(true)}>
            {grouped ? <UngroupIcon size={16} aria-hidden="true" /> : <Grid2X2Icon size={16} aria-hidden="true" />}
          </button>
        </div>
      )}
      <div className="editor-image-frame" data-selected={selected || undefined}>
        <div className="editor-image-media">
          {/* Uploaded images use authenticated local URLs and must retain their natural aspect ratio. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            ref={imageRef}
            draggable={editable}
            data-drag-handle={editable ? "" : undefined}
            src={node.attrs.src}
            alt={node.attrs.alt ?? ""}
            title={node.attrs.title ?? undefined}
            width={grouped ? undefined : preview ?? storedWidth}
            style={{ width: grouped ? "100%" : preview ?? storedWidth }}
          />
          {editable && !grouped && (
            <button
              type="button"
              className="editor-image-resize"
              aria-label={messages.editor.imageResize}
              title={messages.editor.imageResize}
              onClick={(event) => { event.preventDefault(); event.stopPropagation(); }}
              onPointerDown={(event) => {
                if (event.button !== 0) return;
                event.preventDefault();
                event.stopPropagation();
                const width = imageRef.current?.getBoundingClientRect().width ?? 32;
                drag.current = { x: event.clientX, width, next: width };
                event.currentTarget.setPointerCapture(event.pointerId);
              }}
              onPointerMove={(event) => {
                if (!drag.current) return;
                drag.current.next = limit(drag.current.width + event.clientX - drag.current.x);
                setPreview(drag.current.next);
              }}
              onPointerUp={(event) => {
                if (!drag.current) return;
                const width = drag.current.next;
                drag.current = null;
                commit(width);
                setPreview(null);
                event.currentTarget.releasePointerCapture(event.pointerId);
              }}
              onPointerCancel={() => { drag.current = null; setPreview(null); }}
              onLostPointerCapture={() => { drag.current = null; setPreview(null); }}
              onKeyDown={(event) => {
                if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
                event.preventDefault();
                event.stopPropagation();
                const width = imageRef.current?.getBoundingClientRect().width ?? 32;
                commit(width + (event.key === "ArrowRight" ? 10 : -10));
              }}
            />
          )}
          {editable && grouped && (
            <button
              type="button"
              className="editor-gallery-resize"
              aria-label={m.imageGalleryResize}
              onClick={(event) => event.stopPropagation()}
              onPointerDown={(event) => {
                if (event.button !== 0) return;
                event.preventDefault();
                event.stopPropagation();
                const height = imageRef.current?.getBoundingClientRect().height ?? DEFAULT_GALLERY_HEIGHT;
                const frames = currentGallery().flatMap(({ pos }) => {
                  const element = editor.view.nodeDOM(pos);
                  const frame = element instanceof HTMLElement ? element.querySelector<HTMLElement>(".editor-image-media") : null;
                  return frame ? [frame] : [];
                });
                galleryDrag.current = { y: event.clientY, height, next: height, frames };
                event.currentTarget.setPointerCapture(event.pointerId);
              }}
              onPointerMove={(event) => {
                const drag = galleryDrag.current;
                if (!drag) return;
                drag.next = limitGalleryHeight(drag.height + event.clientY - drag.y);
                drag.frames.forEach((frame) => frame.style.setProperty("--gallery-preview-height", drag.next + "px"));
              }}
              onPointerUp={() => {
                const next = galleryDrag.current?.next;
                clearGalleryPreview();
                if (next !== undefined) commitGalleryHeight(next);
              }}
              onPointerCancel={clearGalleryPreview}
              onLostPointerCapture={clearGalleryPreview}
              onKeyDown={(event) => {
                if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
                event.preventDefault();
                event.stopPropagation();
                const height = imageRef.current?.getBoundingClientRect().height ?? DEFAULT_GALLERY_HEIGHT;
                commitGalleryHeight(height + (event.key === "ArrowDown" ? 10 : -10));
              }}
            />
          )}
        </div>
        {editable && editingCaption ? (
          <textarea ref={captionRef} className="editor-image-caption-input" aria-label={m.imageCaption} placeholder={m.imageCaptionPlaceholder}
            defaultValue={node.attrs.caption ?? ""} maxLength={500} rows={2}
            onKeyDown={(event) => {
              event.stopPropagation();
              if (event.key === "Escape" && !event.nativeEvent.isComposing) {
                event.preventDefault();
                event.currentTarget.blur();
              }
            }}
            onBlur={(event) => {
              // Let the browser finish IME composition before updating the document node.
              const caption = event.currentTarget.value.slice(0, 500) || null;
              setEditingCaption(false);
              if (editor.isEditable && caption !== node.attrs.caption) updateAttributes({ caption });
            }} />
        ) : (node.attrs.caption || grouped) ? (
          <div className="editor-image-caption" onClick={() => { if (editable) setEditingCaption(true); }}>{node.attrs.caption ?? null}</div>
        ) : null}
      </div>
      {editable && choosingGroup && available >= 2 && (
        <select
          ref={groupSelect}
          className="mt-2 block max-w-full rounded border border-border bg-background px-2 py-1 text-sm"
          aria-label={m.imageGroupCount}
          value=""
          onBlur={() => setChoosingGroup(false)}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.stopPropagation();
              event.currentTarget.blur();
            }
          }}
          onChange={(event) => {
            arrange(Number(event.target.value));
            setChoosingGroup(false);
          }}
        >
          <option value="" disabled>{m.imageGroupCount}</option>
          {Array.from({ length: available - 1 }, (_, index) => (
            <option key={index + 2} value={index + 2}>{m.imageGroupOption(index + 2)}</option>
          ))}
        </select>
      )}
    </NodeViewWrapper>
  );
}

export const ResizableImage = Image.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      caption: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-image-caption"),
        renderHTML: (attrs) => attrs.caption ? { "data-image-caption": attrs.caption } : {},
      },
      align: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-image-align"),
        renderHTML: (attrs) => attrs.align ? { "data-image-align": attrs.align } : {},
      },
      galleryHeight: {
        default: null,
        parseHTML: (element) => {
          const height = Number(element.getAttribute("data-gallery-height"));
          return Number.isInteger(height) && height === limitGalleryHeight(height) ? height : null;
        },
        renderHTML: (attrs) => attrs.galleryHeight ? { "data-gallery-height": attrs.galleryHeight } : {},
      },
      imageGroup: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-image-group"),
        renderHTML: (attrs) => attrs.imageGroup ? { "data-image-group": attrs.imageGroup } : {},
      },
    };
  },
  addProseMirrorPlugins() {
    return [...(this.parent?.() ?? []), imageGalleryPlugin];
  },
  addNodeView() {
    return ReactNodeViewRenderer(ImageNode);
  },
});
