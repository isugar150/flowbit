"use client";

import Image from "@tiptap/extension-image";
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from "@tiptap/react";
import { useEffect, useRef, useState } from "react";
import { messages } from "@/messages";

function ImageNode({ node, editor, updateAttributes, selected }: NodeViewProps) {
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

  function limit(width: number) {
    const available = imageRef.current?.closest<HTMLElement>(".tiptap")?.clientWidth ?? 1600;
    return Math.max(1, Math.min(available, Math.max(32, Math.round(width))));
  }

  function commit(width: number) {
    if (editor.isEditable) updateAttributes({ width: limit(width), height: null });
  }

  return (
    <NodeViewWrapper className="editor-image" data-block-id={node.attrs.blockId ?? undefined} contentEditable={false}>
      <div className="editor-image-frame" data-selected={selected || undefined}>
        {/* Uploaded images use authenticated local URLs and must retain their natural aspect ratio. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          ref={imageRef}
          src={node.attrs.src}
          alt={node.attrs.alt ?? ""}
          title={node.attrs.title ?? undefined}
          width={preview ?? storedWidth}
          style={{ width: preview ?? storedWidth, height: "auto" }}
        />
        {editable && (
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
  addNodeView() {
    return ReactNodeViewRenderer(ImageNode);
  },
});
