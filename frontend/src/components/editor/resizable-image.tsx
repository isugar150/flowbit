"use client";

import Image from "@tiptap/extension-image";
import { NodeViewWrapper, ReactNodeViewRenderer, useEditorState, type NodeViewProps } from "@tiptap/react";
import { useEffect, useRef, useState } from "react";
import { Grid2X2Icon, UngroupIcon, TextAlignStartIcon, TextAlignCenterIcon, TextAlignEndIcon } from "lucide-react";
import { messages } from "@/messages";
import { newBlockId } from "./block-id";
import { imageGalleryPlugin, imageRun } from "./image-layout";

function ImageNode({ node, editor, updateAttributes, selected, getPos }: NodeViewProps) {
  const imageRef = useRef<HTMLImageElement>(null);
  const drag = useRef<{ x: number; width: number; next: number } | null>(null);
  const [preview, setPreview] = useState<number | null>(null);
  const [editable, setEditable] = useState(editor.isEditable);
  useEffect(() => {
    const sync = () => setEditable(editor.isEditable);
    editor.on("update", sync);
    return () => { editor.off("update", sync); };
  }, [editor]);
  const storedWidth = typeof node.attrs.width === "number" && node.attrs.width > 0 ? node.attrs.width : undefined;
  const { grouped, canGroup } = useEditorState({ editor, selector: () => {
    const pos = getPos();
    if (pos === undefined || pos >= editor.state.doc.content.size) return { grouped: false, canGroup: false };
    const doc = editor.state.doc;
    const at = doc.resolve(pos);
    if (at.depth !== 0 || at.nodeAfter?.type.name !== "image") return { grouped: false, canGroup: false };
    const index = at.index();
    const neighbors = [index > 0 ? doc.child(index - 1) : null, index + 1 < doc.childCount ? doc.child(index + 1) : null]
      .filter((image) => image?.type.name === "image");
    const group = at.nodeAfter.attrs.imageGroup;
    return {
      canGroup: neighbors.length > 0,
      grouped: Boolean(group) && neighbors.some((image) => image?.attrs.imageGroup === group),
    };
  } });
  const m = messages.editor;

  function arrange() {
    const pos = getPos();
    if (!editor.isEditable || pos === undefined) return;
    const run = imageRun(editor.state.doc, pos, grouped ? node.attrs.imageGroup : undefined);
    if (!grouped && run.length < 2) return;
    const imageGroup = grouped ? null : newBlockId();
    const transaction = editor.state.tr;
    run.forEach((image) => transaction.setNodeMarkup(image.pos, undefined, { ...image.node.attrs, imageGroup }));
    editor.view.dispatch(transaction);
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
          <button type="button" aria-label={grouped ? m.imageUngroup : m.imageAutoLayout} title={grouped ? m.imageUngroup : m.imageAutoLayout}
            disabled={!grouped && !canGroup} onMouseDown={(event) => event.preventDefault()} onClick={arrange}>
            {grouped ? <UngroupIcon size={16} aria-hidden="true" /> : <Grid2X2Icon size={16} aria-hidden="true" />}
          </button>
        </div>
      )}
      <div className="editor-image-frame" data-selected={selected || undefined}>
        {/* Uploaded images use authenticated local URLs and must retain their natural aspect ratio. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          ref={imageRef}
          src={node.attrs.src}
          alt={node.attrs.alt ?? ""}
          title={node.attrs.title ?? undefined}
          width={grouped ? undefined : preview ?? storedWidth}
          style={{ width: grouped ? "100%" : preview ?? storedWidth, height: "auto" }}
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
      </div>
    </NodeViewWrapper>
  );
}

export const ResizableImage = Image.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      align: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-image-align"),
        renderHTML: (attrs) => attrs.align ? { "data-image-align": attrs.align } : {},
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
