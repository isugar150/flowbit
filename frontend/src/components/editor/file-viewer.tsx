"use client";

import { useEffect, useState } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { DownloadIcon, FileTextIcon, XIcon } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { apiBytes } from "@/lib/api/client";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { fileBlockHref } from "./file-block";
import { isPreviewable, previewKindOf, type PreviewKind } from "./file-preview";

const m = messages.editor;

/** A file of the text that is opened to be read: where it is stored, the name it was uploaded with and its size. */
export type ViewerFile = { src: string; name: string; size: number };

/** What the three viewers of the library have in common. */
type OfficeViewer = { load(source: ArrayBuffer): Promise<void>; destroy(): void };

/**
 * Starts the viewer for one kind of file in `container`. Only the format that is opened is fetched: the library is split per
 * format, and each brings its own parser (WebAssembly) with it.
 *
 * Nothing leaves the browser: the library makes no request of its own unless `useGoogleFonts` is turned on, and it is not. Text
 * that has no Korean letters but Han characters is drawn with the Korean forms of them (`cjkFallback`).
 */
async function createViewer(kind: PreviewKind, container: HTMLElement): Promise<OfficeViewer> {
  const surface = { background: "transparent", gap: 16, paddingTop: 16, enableTextSelection: true, cjkFallback: "kr" } as const;
  if (kind === "docx") {
    const { DocxScrollViewer } = await import("@silurus/ooxml/docx");
    return new DocxScrollViewer(container, surface);
  }
  if (kind === "pptx") {
    const { PptxScrollViewer } = await import("@silurus/ooxml/pptx");
    return new PptxScrollViewer(container, surface);
  }
  const { XlsxViewer } = await import("@silurus/ooxml/xlsx");
  return new XlsxViewer(container, { cjkFallback: "kr" });
}

type Stage = "loading" | "ready" | "failed";

/**
 * A Word, Excel or PowerPoint file of a text read over the page (D-187): it opens when the file's block is clicked and closes
 * with Esc, the X or a click beside it. The file is fetched from the server as it is stored and drawn in the browser, so
 * nothing is converted or sent anywhere. It cannot be changed, only read, selected and copied, zoomed (Ctrl + wheel, pinch) and
 * saved.
 *
 * Built on the dialog primitive, like the picture viewer, so that opened from the new-ticket dialog it is part of that
 * dialog's stack.
 */
export function FileViewer({ file, onClose }: { file: ViewerFile; onClose: () => void }) {
  const kind = previewKindOf(file.name);
  const readable = kind !== null && isPreviewable(file.name, file.size);
  // The surface is in a portal, which fills in a render after this one: the effect waits for it rather than reading a ref.
  const [surface, setSurface] = useState<HTMLDivElement | null>(null);
  const [stage, setStage] = useState<Stage>(readable ? "loading" : "failed");

  useEffect(() => {
    const container = surface;
    if (!container || !kind || !readable) return;
    let cancelled = false;
    let viewer: OfficeViewer | null = null;
    (async () => {
      try {
        const [bytes, created] = await Promise.all([apiBytes(file.src), createViewer(kind, container)]);
        if (cancelled) {
          created.destroy();
          return;
        }
        viewer = created;
        await created.load(bytes);
        if (!cancelled) setStage("ready");
      } catch {
        if (!cancelled) setStage("failed");
      }
    })();
    return () => {
      cancelled = true;
      viewer?.destroy();
    };
  }, [file.src, kind, readable, surface]);

  const download = fileBlockHref(file.src, file.name);
  const actionClass = buttonVariants({ variant: "ghost", size: "icon" });

  return (
    <DialogPrimitive.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-[70] bg-black/60 duration-100 data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0" />
        <DialogPrimitive.Popup
          className="fixed inset-0 z-[70] flex flex-col overflow-hidden bg-background text-foreground outline-none md:inset-8 md:rounded-xl md:shadow-2xl md:ring-1 md:ring-foreground/10"
        >
          <div className="flex h-12 shrink-0 items-center gap-2 border-b border-border pr-2 pl-4">
            <FileTextIcon className="shrink-0 text-muted-foreground" aria-hidden="true" />
            <DialogPrimitive.Title className="min-w-0 flex-1 truncate text-sm leading-5 font-medium">{file.name}</DialogPrimitive.Title>
            <span className="shrink-0 text-xs leading-4 text-muted-foreground tabular-nums max-sm:hidden">{m.fileSize(file.size)}</span>
            <a href={download} download={file.name} aria-label={m.fileDownload(file.name)} title={m.fileDownload(file.name)} className={actionClass}>
              <DownloadIcon />
            </a>
            <DialogPrimitive.Close aria-label={m.dismiss} title={m.dismiss} className={actionClass}>
              <XIcon />
            </DialogPrimitive.Close>
          </div>

          <div className="relative min-h-0 flex-1 bg-muted">
            <div ref={setSurface} className={cn("absolute inset-0", stage === "failed" && "hidden")} />
            {stage === "loading" ? (
              <p className="absolute inset-0 flex items-center justify-center gap-2 text-sm text-muted-foreground" role="status">
                <Spinner />
                {m.fileViewerLoading}
              </p>
            ) : null}
            {stage === "failed" ? (
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-6 text-center" role="alert">
                <p className="text-sm text-muted-foreground">{m.fileViewerFailed}</p>
                <a href={download} download={file.name} className={buttonVariants({ variant: "outline" })}>
                  <DownloadIcon data-icon="inline-start" />
                  {m.fileDownload(file.name)}
                </a>
              </div>
            ) : null}
          </div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
