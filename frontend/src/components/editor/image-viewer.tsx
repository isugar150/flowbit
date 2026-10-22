"use client";

import { useRef, useState } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import Lightbox, { type ZoomRef } from "yet-another-react-lightbox";
import Counter from "yet-another-react-lightbox/plugins/counter";
import Download from "yet-another-react-lightbox/plugins/download";
import Zoom from "yet-another-react-lightbox/plugins/zoom";
import "yet-another-react-lightbox/styles.css";
import "yet-another-react-lightbox/plugins/counter.css";
import { messages } from "@/messages";

const m = messages.editor;

export type ViewerImage = { src: string; alt: string };

/** The name to offer for the download; the server's own (with the right extension) wins. */
function downloadName(alt: string): string {
  const base = alt.replace(/\.[A-Za-z0-9]{1,5}$/, "").trim();
  return `${base || "image"}.webp`;
}

/**
 * The pictures of a document enlarged over the page (D-182), shown by yet-another-react-lightbox: a dark backdrop, ← → (and
 * swipe, and the arrow buttons) to go through all the pictures of the text, wheel / pinch / buttons / double click to zoom,
 * a click on the picture to zoom in or back out, a counter, a download button, Esc or a click beside the picture to close.
 *
 * It is drawn inside a dialog of its own (the dialog primitive), not on the body: opened from the new-ticket dialog it has to
 * belong to that dialog's stack, or the dialog would read a click on the picture as one outside itself and take the focus back.
 */
export function ImageViewer({
  images,
  index,
  onIndexChange,
  onClose,
}: {
  images: ViewerImage[];
  index: number;
  onIndexChange: (index: number) => void;
  onClose: () => void;
}) {
  // Where the lightbox is drawn: a box inside the dialog's popup.
  const [root, setRoot] = useState<HTMLElement | null>(null);
  const zoom = useRef<ZoomRef>(null);
  // Where the press began, and whether it moved: the click that follows the end of a drag is not a click on the picture.
  const press = useRef<{ x: number; y: number; moved: boolean } | null>(null);

  return (
    <DialogPrimitive.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-[70] bg-black/90" />
        <DialogPrimitive.Popup
          aria-label={m.imageViewer}
          className="fixed inset-0 z-[70] outline-none"
          onPointerDownCapture={(event) => {
            press.current = { x: event.clientX, y: event.clientY, moved: false };
          }}
          onPointerMoveCapture={(event) => {
            const start = press.current;
            if (start && !start.moved && Math.hypot(event.clientX - start.x, event.clientY - start.y) > 4) start.moved = true;
          }}
        >
          <DialogPrimitive.Title className="sr-only">{m.imageViewer}</DialogPrimitive.Title>
          <div ref={setRoot} />
          {root ? (
            <Lightbox
              open
              close={onClose}
              index={index}
              portal={{ root }}
              plugins={[Zoom, Counter, Download]}
              zoom={{ ref: zoom, scrollToZoom: true, maxZoomPixelRatio: 4 }}
              counter={{ container: { style: { top: "unset", bottom: 0 } } }}
              slides={images.map((image) => ({
                src: image.src,
                alt: image.alt,
                // The picture as it was uploaded; the server falls back to the WebP copy when none was kept.
                download: { url: `${image.src}/original?name=${encodeURIComponent(image.alt)}`, filename: downloadName(image.alt) },
              }))}
              // The arrows stay out when there is only one picture.
              carousel={{ finite: images.length <= 1 }}
              render={images.length <= 1 ? { buttonPrev: () => null, buttonNext: () => null } : undefined}
              on={{
                view: ({ index: next }) => onIndexChange(next),
                // A click on the picture zooms in, another one back out. Moving a zoomed picture only moves it.
                click: () => {
                  if (press.current?.moved) return;
                  const current = zoom.current;
                  if (current) current.changeZoom(current.zoom > 1 ? 1 : 2.5);
                },
              }}
              controller={{ closeOnBackdropClick: true }}
              styles={{ container: { backgroundColor: "rgba(0, 0, 0, 0.9)" } }}
              labels={{
                Previous: m.imagePrevious,
                Next: m.imageNext,
                Close: m.imageClose,
                "Zoom in": m.imageZoomIn,
                "Zoom out": m.imageZoomOut,
                Download: m.imageDownload,
              }}
              animation={{ fade: 150 }}
            />
          ) : null}
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
