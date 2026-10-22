import { isTextKind, type PreviewKind, type ViewerStage } from "./file-preview";
import { loadOfficeViewer } from "./office-viewer";
import { loadTextViewer } from "./text-viewer";

/**
 * Shows the stored file `src` in `container` as its kind says (D-187): the editor's inline preview and the window over the page
 * both use this. `onStage` hears when it is shown or could not be. Returns what stops it and takes the viewer down; call it when
 * the container goes away.
 *
 * `compact` is for a small surface inside the text, which has less room for the margins between pages. The caller gives the
 * container a size (a Word, PowerPoint or PDF file fills it) and, for text and tables, its scrolling.
 *
 * The libraries are not part of the page: each is fetched when a file of its kind is shown.
 */
export function loadPreview(
  kind: PreviewKind,
  container: HTMLElement,
  src: string,
  onStage: (stage: ViewerStage) => void,
  options: { compact?: boolean } = {},
): () => void {
  if (isTextKind(kind)) return loadTextViewer(container, src, onStage);
  if (kind === "pdf") {
    let cancelled = false;
    let stop: (() => void) | null = null;
    import("./pdf-viewer")
      .then(({ loadPdfViewer }) => {
        if (!cancelled) stop = loadPdfViewer(container, src, onStage, options);
      })
      .catch(() => {
        if (!cancelled) onStage("failed");
      });
    return () => {
      cancelled = true;
      stop?.();
    };
  }
  return loadOfficeViewer(kind, container, src, onStage, options);
}
