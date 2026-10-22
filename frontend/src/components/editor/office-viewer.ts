import { apiBytes } from "@/lib/api/client";
import type { OfficeKind, ViewerStage } from "./file-preview";
import { decodeText, detectDelimiter } from "./text-file";

/** What the three viewers of the library have in common. */
type OfficeViewer = { load(source: ArrayBuffer): Promise<void>; destroy(): void };

/**
 * Starts the viewer for one kind of file in `container`. Only the format that is opened is fetched: the library is split per
 * format, and each brings its own parser (WebAssembly) with it.
 *
 * Nothing leaves the browser: the library makes no request of its own unless `useGoogleFonts` is turned on, and it is not. Text
 * that has no Korean letters but Han characters is drawn with the Korean forms of them (`cjkFallback`).
 */
async function createViewer(kind: OfficeKind, container: HTMLElement, compact: boolean): Promise<OfficeViewer> {
  const space = compact ? 8 : 16;
  const surface = { background: "transparent", gap: space, paddingTop: space, enableTextSelection: true, cjkFallback: "kr" } as const;
  if (kind === "docx") {
    const { DocxScrollViewer } = await import("@silurus/ooxml/docx");
    return new DocxScrollViewer(container, surface);
  }
  if (kind === "pptx") {
    const { PptxScrollViewer } = await import("@silurus/ooxml/pptx");
    return new PptxScrollViewer(container, surface);
  }
  if (kind === "csv" || kind === "tsv") {
    // A CSV or TSV file is the one sheet of a workbook, drawn like any other (every cell stays the text it is: no dates, no formulas).
    const { XlsxSheetViewer } = await import("@silurus/ooxml/xlsx");
    const canvas = document.createElement("canvas");
    canvas.style.cssText = "display:block;width:100%;height:100%";
    container.append(canvas);
    const sheet = new XlsxSheetViewer(canvas, { cjkFallback: "kr" });
    return {
      load: (bytes) => {
        // Read here, not by the library: files from Korean programs are often not UTF-8 (text-file.ts).
        const text = decodeText(bytes);
        const utf8 = new TextEncoder().encode(text).buffer as ArrayBuffer;
        const delimiter = kind === "tsv" ? "	" : detectDelimiter(text);
        return sheet.load(utf8, delimiter === "," || delimiter === "	" ? { format: delimiter === "," ? "csv" : "tsv", encoding: "utf-8" } : { format: "delimited-text", delimiter, encoding: "utf-8" });
      },
      destroy: () => {
        sheet.destroy();
        canvas.remove();
      },
    };
  }
  const { XlsxViewer } = await import("@silurus/ooxml/xlsx");
  return new XlsxViewer(container, { cjkFallback: "kr" });
}

/**
 * Reads the stored file `src` and draws it in `container` (D-187): the editor's inline preview and the window over the page both
 * use this. `onStage` hears when it is shown or could not be. Returns what stops it (the file, if still being read, is dropped)
 * and takes the viewer down; call it when the container goes away.
 *
 * `compact` is for a small surface inside the text, which has less room for the margins between pages.
 */
export function loadOfficeViewer(
  kind: OfficeKind,
  container: HTMLElement,
  src: string,
  onStage: (stage: ViewerStage) => void,
  { compact = false }: { compact?: boolean } = {},
): () => void {
  let cancelled = false;
  let viewer: OfficeViewer | null = null;
  (async () => {
    try {
      const [bytes, created] = await Promise.all([apiBytes(src), createViewer(kind, container, compact)]);
      if (cancelled) {
        created.destroy();
        return;
      }
      viewer = created;
      await created.load(bytes);
      if (!cancelled) onStage("ready");
    } catch {
      if (!cancelled) onStage("failed");
    }
  })();
  return () => {
    cancelled = true;
    viewer?.destroy();
  };
}
