"use client";

import { useEffect, useRef, useState } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { ChevronLeftIcon, ChevronRightIcon, DownloadIcon, ExternalLinkIcon, MinusIcon, PlusIcon, ScanIcon, XIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.editor;

export type ViewerImage = { src: string; alt: string };

const MIN_SCALE = 0.5;
const MAX_SCALE = 8;
const STEP = 1.25;
/** What a double click zooms to from the fitted size. */
const DOUBLE_CLICK_SCALE = 2.5;

const clamp = (scale: number) => Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale));

type View = { scale: number; x: number; y: number };

/** Back at (or below) the fitted size the picture is centred again. */
const withScale = (current: View, scale: number): View => {
  const next = clamp(scale);
  return next <= 1 ? { scale: next, x: 0, y: 0 } : { ...current, scale: next };
};

/** The name a picture is saved under: the file name it was uploaded with, as a WebP (that is what the server keeps). */
function downloadName(alt: string): string {
  const base = alt.replace(/\.[A-Za-z0-9]{1,5}$/, "").trim();
  return `${base || "image"}.webp`;
}

const buttonClass =
  "flex size-9 items-center justify-center rounded-full text-white outline-none hover:bg-white/15 focus-visible:ring-3 focus-visible:ring-white/50 disabled:opacity-40 max-md:size-11 [&>svg]:size-5";

function ViewerButton({
  label,
  onClick,
  disabled,
  className,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <button type="button" aria-label={label} title={label} disabled={disabled} onClick={onClick} className={cn(buttonClass, className)}>
      {children}
    </button>
  );
}

/**
 * A picture of a document enlarged over the page, the way Notion shows one: it opens when the picture is clicked and
 * closes with Esc, the X or a click beside it (D-182). The pictures of the same document can be paged through (← →);
 * the wheel, the buttons and +/- zoom, a zoomed picture is dragged around, a double click toggles the zoom, "0" fits it
 * back to the screen. It can be saved or opened on its own in a new tab.
 *
 * Built on the dialog primitive, not hung on the body: opened from the new-ticket dialog it must be part of that
 * dialog's stack, or the dialog would take the focus back and read a click on the picture as one outside itself.
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
  const image = images[index];
  const [view, setView] = useState<View>({ scale: 1, x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const [shownIndex, setShownIndex] = useState(index);
  const stageRef = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; originX: number; originY: number } | null>(null);

  // Another picture starts fitted, not zoomed and moved like the one before. Adjusted while rendering.
  if (shownIndex !== index) {
    setShownIndex(index);
    setView({ scale: 1, x: 0, y: 0 });
  }

  const zoomTo = (scale: number) => setView((current) => withScale(current, scale));
  const zoomBy = (factor: number) => setView((current) => withScale(current, current.scale * factor));
  const go = (step: number) => {
    if (images.length > 1) onIndexChange((index + step + images.length) % images.length);
  };

  // The wheel zooms. It must be a listener of its own: React's wheel handlers are passive and could not stop the page behind from scrolling.
  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      setView((current) => withScale(current, current.scale * (event.deltaY < 0 ? STEP : 1 / STEP)));
    };
    stage.addEventListener("wheel", onWheel, { passive: false });
    return () => stage.removeEventListener("wheel", onWheel);
  }, [image]);

  if (!image) return null;
  const zoomed = view.scale > 1;
  const endDrag = () => {
    drag.current = null;
    setDragging(false);
  };

  return (
    <DialogPrimitive.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-[70] bg-black/85 duration-100 data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0" />
        <DialogPrimitive.Popup
          aria-label={m.imageViewer}
          className="fixed inset-0 z-[70] flex items-center justify-center text-white outline-none"
          onKeyDown={(event) => {
            if (event.key === "ArrowLeft") go(-1);
            else if (event.key === "ArrowRight") go(1);
            else if (event.key === "+" || event.key === "=") zoomBy(STEP);
            else if (event.key === "-") zoomBy(1 / STEP);
            else if (event.key === "0") zoomTo(1);
            else return;
            event.preventDefault();
          }}
        >
          <DialogPrimitive.Title className="sr-only">{image.alt || m.imageViewer}</DialogPrimitive.Title>
          <div
            ref={stageRef}
            className="absolute inset-0 flex items-center justify-center overflow-hidden"
            // A click on the dark beside the picture closes it.
            onClick={(event) => {
              if (event.target === event.currentTarget) onClose();
            }}
          >
            {/* eslint-disable-next-line @next/next/no-img-element -- a stored WebP behind the sign-in; the optimiser could not read it */}
            <img
              key={image.src}
              src={image.src}
              alt={image.alt}
              draggable={false}
              onDoubleClick={() => zoomTo(zoomed ? 1 : DOUBLE_CLICK_SCALE)}
              onPointerDown={(event) => {
                if (!zoomed) return;
                event.currentTarget.setPointerCapture(event.pointerId);
                drag.current = { x: event.clientX, y: event.clientY, originX: view.x, originY: view.y };
                setDragging(true);
              }}
              onPointerMove={(event) => {
                const start = drag.current;
                if (!start) return;
                setView((current) => ({ ...current, x: start.originX + event.clientX - start.x, y: start.originY + event.clientY - start.y }));
              }}
              onPointerUp={endDrag}
              onPointerCancel={endDrag}
              style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` }}
              className={cn(
                "max-h-[calc(100dvh-8rem)] max-w-[calc(100vw-2rem)] touch-none object-contain select-none",
                !dragging && "transition-transform duration-100",
                zoomed ? (dragging ? "cursor-grabbing" : "cursor-grab") : "cursor-zoom-in",
              )}
            />
          </div>

          <div className="absolute top-0 right-0 left-0 flex items-center justify-end gap-1 p-3 pt-[max(0.75rem,env(safe-area-inset-top))]">
            <ViewerButton label={m.imageZoomOut} onClick={() => zoomBy(1 / STEP)} disabled={view.scale <= MIN_SCALE}>
              <MinusIcon />
            </ViewerButton>
            <span className="w-12 text-center text-[0.8125rem] leading-5 tabular-nums" aria-live="polite">
              {Math.round(view.scale * 100)}%
            </span>
            <ViewerButton label={m.imageZoomIn} onClick={() => zoomBy(STEP)} disabled={view.scale >= MAX_SCALE}>
              <PlusIcon />
            </ViewerButton>
            <ViewerButton label={m.imageZoomReset} onClick={() => zoomTo(1)}>
              <ScanIcon />
            </ViewerButton>
            <span className="mx-1 h-5 w-px bg-white/25" aria-hidden="true" />
            <a href={image.src} target="_blank" rel="noopener noreferrer" aria-label={m.imageOpenTab} title={m.imageOpenTab} className={buttonClass}>
              <ExternalLinkIcon />
            </a>
            <a href={image.src} download={downloadName(image.alt)} aria-label={m.imageDownload} title={m.imageDownload} className={buttonClass}>
              <DownloadIcon />
            </a>
            <DialogPrimitive.Close aria-label={m.imageClose} title={m.imageClose} className={buttonClass}>
              <XIcon />
            </DialogPrimitive.Close>
          </div>

          {images.length > 1 ? (
            <>
              <ViewerButton label={m.imagePrevious} onClick={() => go(-1)} className="absolute top-1/2 left-3 -translate-y-1/2 bg-black/40">
                <ChevronLeftIcon />
              </ViewerButton>
              <ViewerButton label={m.imageNext} onClick={() => go(1)} className="absolute top-1/2 right-3 -translate-y-1/2 bg-black/40">
                <ChevronRightIcon />
              </ViewerButton>
              <p
                className="absolute bottom-4 left-1/2 -translate-x-1/2 rounded-full bg-black/50 px-3 py-1 text-[0.8125rem] leading-5 tabular-nums"
                aria-live="polite"
              >
                {m.imageCount(index + 1, images.length)}
              </p>
            </>
          ) : null}
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
