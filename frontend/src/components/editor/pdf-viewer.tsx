"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import { Document, Page, pdfjs } from "react-pdf";
import "react-pdf/dist/Page/AnnotationLayer.css";
import "react-pdf/dist/Page/TextLayer.css";
import { apiBytes } from "@/lib/api/client";
import { messages } from "@/messages";
import type { ViewerStage } from "./file-preview";

const m = messages.editor;

// The reader runs in a worker of its own, which the bundler copies out of the package.
pdfjs.GlobalWorkerOptions.workerSrc = new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url).toString();

/** A page is never drawn wider than this, however wide the window: a line that long is not read. */
const MAX_PAGE_WIDTH = 960;
const MIN_ZOOM = 0.5;
const MAX_ZOOM = 3;
const ZOOM_STEP = 1.1;
/** Pages this far (in pixels) from the visible part are drawn too, so that scrolling does not show them blank. */
const LOOK_AHEAD = 800;

const clamp = (zoom: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom));

/**
 * One page. Only the pages near the visible part are drawn (a PDF of a hundred pages is a hundred canvases); the others are
 * an empty sheet of the same size, so that the scroll bar and the page count stay where they are.
 */
function LazyPage({ number, width, ratio, root }: { number: number; width: number; ratio: number; root: HTMLElement | null }) {
  const box = useRef<HTMLDivElement>(null);
  const [near, setNear] = useState(number <= 2);
  const [own, setOwn] = useState<number | null>(null);

  useEffect(() => {
    const element = box.current;
    if (!element || !root) return;
    const watch = new IntersectionObserver(([entry]) => setNear(entry.isIntersecting), { root, rootMargin: `${LOOK_AHEAD}px 0px` });
    watch.observe(element);
    return () => watch.disconnect();
  }, [root]);

  return (
    // A sheet of paper is white whatever the theme is.
    <div ref={box} data-pdf-page className="relative shrink-0 bg-white shadow-sm ring-1 ring-black/10" style={{ width, height: width * (own ?? ratio) }}>
      {near ? (
        <Page
          pageNumber={number}
          width={width}
          renderTextLayer
          renderAnnotationLayer
          suspense={false}
          loading={null}
          onLoadSuccess={(page) => setOwn(page.originalHeight / page.originalWidth)}
        />
      ) : null}
    </div>
  );
}

function PdfPages({ data, compact, onReady, onFail }: { data: Uint8Array; compact: boolean; onReady: () => void; onFail: () => void }) {
  // The reader takes the bytes over, so it is given a copy of its own.
  const file = useMemo(() => ({ data: data.slice() }), [data]);
  const [box, setBox] = useState<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);
  const [zoom, setZoom] = useState(1);
  const [pages, setPages] = useState(0);
  const [ratio, setRatio] = useState(Math.SQRT2);
  const [current, setCurrent] = useState(1);
  const frame = useRef(0);
  const space = compact ? 8 : 16;

  useEffect(() => {
    if (!box) return;
    const watch = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    watch.observe(box);
    // Ctrl (⌘) and the wheel zoom, as in the other viewers. A listener of its own: React's wheel handlers are passive and could
    // not keep the page behind from zooming.
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      setZoom((zoom) => clamp(zoom * (event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP)));
    };
    box.addEventListener("wheel", onWheel, { passive: false });
    return () => {
      watch.disconnect();
      box.removeEventListener("wheel", onWheel);
    };
  }, [box]);

  // The page number shown is the one at the upper third of what is visible.
  const onScroll = () => {
    if (frame.current || !box) return;
    frame.current = requestAnimationFrame(() => {
      frame.current = 0;
      const probe = box.scrollTop + box.clientHeight / 3;
      let page = 1;
      box.querySelectorAll<HTMLElement>("[data-pdf-page]").forEach((element, index) => {
        if (element.offsetTop <= probe) page = index + 1;
      });
      setCurrent(page);
    });
  };
  useEffect(() => () => cancelAnimationFrame(frame.current), []);

  const pageWidth = Math.max(120, Math.min(width - space * 2, MAX_PAGE_WIDTH)) * zoom;

  return (
    <div className="relative h-full">
      <div ref={setBox} onScroll={onScroll} className="relative h-full overflow-auto">
        <Document
          file={file}
          // Loads in an effect: there is no Suspense boundary above this root to wait in.
          suspense={false}
          loading={null}
          error={null}
          noData={null}
          externalLinkTarget="_blank"
          externalLinkRel="noopener noreferrer"
          onLoadSuccess={async (pdf) => {
            setPages(pdf.numPages);
            try {
              const view = (await pdf.getPage(1)).getViewport({ scale: 1 });
              setRatio(view.height / view.width);
            } catch {
              // The sheets are drawn at the usual shape of one until their own is known.
            }
            onReady();
          }}
          onLoadError={onFail}
          onSourceError={onFail}
        >
          {/* As wide as its widest page, and at least as wide as the box: a zoomed page scrolls sideways with its left edge reachable. */}
          <div className="flex w-max min-w-full flex-col items-center" style={{ gap: space, padding: space }}>
            {width > 0 ? Array.from({ length: pages }, (_, index) => <LazyPage key={index} number={index + 1} width={pageWidth} ratio={ratio} root={box} />) : null}
          </div>
        </Document>
      </div>
      {pages > 1 ? (
        <p className="pointer-events-none absolute right-3 bottom-3 m-0 rounded-full bg-foreground/70 px-2.5 py-0.5 text-xs text-background tabular-nums" aria-live="polite">
          {m.pdfPage(current, pages)}
        </p>
      ) : null}
    </div>
  );
}

/**
 * Shows a PDF in `container` (D-187): its pages one under the other, drawn when they come near, with the text selectable and
 * the links working. The bytes are fetched from the server as they are stored and read in the browser by pdf.js (through
 * react-pdf). Returns what stops it and takes it down.
 */
export function loadPdfViewer(
  container: HTMLElement,
  src: string,
  onStage: (stage: ViewerStage) => void,
  { compact = false }: { compact?: boolean } = {},
): () => void {
  let cancelled = false;
  let root: Root | null = null;
  (async () => {
    try {
      const bytes = await apiBytes(src);
      if (cancelled) return;
      root = createRoot(container);
      root.render(
        <PdfPages
          data={new Uint8Array(bytes)}
          compact={compact}
          onReady={() => {
            if (!cancelled) onStage("ready");
          }}
          onFail={() => {
            if (!cancelled) onStage("failed");
          }}
        />,
      );
    } catch {
      if (!cancelled) onStage("failed");
    }
  })();
  return () => {
    cancelled = true;
    const mounted = root;
    root = null;
    // Not now: this may be called while React is drawing the editor around it, and a root cannot be taken down in the middle of that.
    if (mounted) queueMicrotask(() => mounted.unmount());
  };
}
