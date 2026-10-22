import { Node } from "@tiptap/core";
import { messages } from "@/messages";
import { isPreviewable, isTextKind, previewKindOf } from "./file-preview";
import { loadPreview } from "./preview-loader";

const m = messages.editor;

/** Where an uploaded file is served from; the same pattern as `STORED_FILE` in RichText.kt. */
const STORED_FILE = /^\/api\/v1\/editor-files\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** The address that downloads the file under its own name. */
export const fileBlockHref = (src: string, name: string) => `${src}?name=${encodeURIComponent(name)}`;

const FILE_ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/></svg>';
const DOWNLOAD_ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 15V3"/><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5"/></svg>';
const EXPAND_ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 3h6v6"/><path d="m21 3-7 7"/><path d="m3 21 7-7"/><path d="M9 21H3v-6"/></svg>';

const FOCUS = "outline-none focus-visible:ring-3 focus-visible:ring-ring/50";
/** A small button of the bar under a previewed file. */
const BAR_BUTTON = `flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground no-underline hover:bg-muted hover:text-foreground max-md:size-10 ${FOCUS}`;

/** The nearest box around `element` that scrolls, or null for the page itself. */
function scrollBoxOf(element: HTMLElement): HTMLElement | null {
  for (let parent = element.parentElement; parent; parent = parent.parentElement) {
    if (/(auto|scroll|overlay)/.test(getComputedStyle(parent).overflowY)) return parent;
  }
  return null;
}

/** The icon, the name and the size as the line of text that tells which file this is. */
function nameAndSize(name: string, size: number): HTMLElement[] {
  const icon = document.createElement("span");
  icon.className = "flex shrink-0 text-muted-foreground";
  icon.innerHTML = FILE_ICON;
  const text = document.createElement("span");
  text.className = "flex min-w-0 flex-1 flex-col";
  const title = document.createElement("span");
  title.className = "truncate text-sm leading-5 font-medium";
  title.textContent = name;
  const meta = document.createElement("span");
  meta.className = "text-xs leading-4 text-muted-foreground tabular-nums";
  meta.textContent = m.fileSize(size);
  text.append(title, meta);
  return [icon, text];
}

/**
 * A file placed in the text as a block of its own (D-182). The block keeps the file's address, name and size; the file itself
 * is on the server. A file that cannot be previewed is an icon, the name and the size, which download it.
 *
 * A Word, Excel, PowerPoint, PDF, text or CSV file is shown in the text itself, the way a Figma or Google Sheets link is (D-187):
 * the document in a window of its own height, and under it a bar with the name, a button that opens it large over the page
 * (`data-file-preview`, the editor opens that) and the download.
 */
export const FileBlock = Node.create({
  name: "fileBlock",
  group: "block",
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return { src: { default: null }, name: { default: "" }, size: { default: 0 } };
  },

  parseHTML() {
    return [
      {
        tag: "div[data-file-block]",
        getAttrs: (element) => {
          const src = element.getAttribute("data-file-block") ?? "";
          return STORED_FILE.test(src)
            ? { src, name: element.getAttribute("data-name") ?? "", size: Number(element.getAttribute("data-size")) || 0 }
            : false;
        },
      },
    ];
  },

  renderHTML({ node }) {
    return ["div", { "data-file-block": node.attrs.src, "data-name": node.attrs.name, "data-size": String(node.attrs.size ?? 0) }];
  },

  addNodeView() {
    return ({ node }) => {
      const src = String(node.attrs.src ?? "");
      const name = String(node.attrs.name ?? "") || m.file;
      const size = Number(node.attrs.size) || 0;
      const stored = STORED_FILE.test(src);
      const kind = stored ? previewKindOf(name) : null;
      const dom = document.createElement("div");
      dom.contentEditable = "false";
      dom.setAttribute("data-file-block", src);
      dom.setAttribute("data-name", name);
      dom.setAttribute("data-size", String(size));
      dom.className = "rich-text-file my-2 [&.ProseMirror-selectednode]:ring-2 [&.ProseMirror-selectednode]:ring-ring";
      const href = stored ? fileBlockHref(src, name) : "#";

      if (!kind || !isPreviewable(name, size)) {
        const link = document.createElement("a");
        link.href = href;
        link.download = name;
        link.draggable = false;
        link.title = m.fileDownload(name);
        link.setAttribute("aria-label", m.fileDownload(name));
        link.className = `flex min-h-12 max-w-full items-center gap-3 rounded-lg border border-border bg-card px-3 py-2 text-foreground no-underline hover:bg-muted ${FOCUS}`;
        const download = document.createElement("span");
        download.className = "flex shrink-0 text-muted-foreground";
        download.innerHTML = DOWNLOAD_ICON;
        link.append(...nameAndSize(name, size), download);
        dom.append(link);
        return { dom, update: (updated) => updated.type === node.type && updated.attrs.src === node.attrs.src };
      }

      // Text and tables are as high as they are, up to a limit, and scroll in it; the other kinds are a window of a height of their own.
      const textLike = isTextKind(kind);
      // Takes the width it is given and never asks for more, like the embed (a viewer that is wider than its box would widen
      // every grid and flex box around the editor).
      dom.style.contain = "inline-size";
      const card = document.createElement("div");
      card.className = "overflow-hidden rounded-lg border border-border bg-card text-foreground";

      const body = document.createElement("div");
      body.className = textLike ? "relative max-h-[28rem] min-h-16 overflow-auto bg-background max-md:max-h-80" : "relative h-[28rem] bg-muted max-md:h-80";
      const surface = document.createElement("div");
      surface.className = textLike ? "relative" : "absolute inset-0";
      const status = document.createElement("p");
      status.className = "absolute inset-0 flex items-center justify-center p-6 text-center text-sm text-muted-foreground";
      status.setAttribute("role", "status");
      status.textContent = m.fileViewerLoading;
      body.append(surface, status);

      const bar = document.createElement("div");
      bar.className = "flex min-h-12 items-center gap-3 border-t border-border px-3 py-1.5";
      const open = document.createElement("button");
      open.type = "button";
      open.setAttribute("data-file-preview", "");
      open.title = m.filePreview(name);
      open.setAttribute("aria-label", m.filePreview(name));
      open.className = BAR_BUTTON;
      open.innerHTML = EXPAND_ICON;
      const download = document.createElement("a");
      download.href = href;
      download.download = name;
      download.draggable = false;
      download.title = m.fileDownload(name);
      download.setAttribute("aria-label", m.fileDownload(name));
      download.className = BAR_BUTTON;
      download.innerHTML = DOWNLOAD_ICON;
      bar.append(...nameAndSize(name, size), open, download);
      card.append(body, bar);
      dom.append(card);

      // The document is drawn when the block comes near the screen, not when the text is opened: a ticket may hold several, and
      // each is a file to fetch and a parser to start.
      let stop: (() => void) | null = null;
      let watch: IntersectionObserver | null = null;
      let gone = false;
      const start = () => {
        stop = loadPreview(
          kind,
          surface,
          src,
          (stage) => {
            if (stage === "ready") {
              status.remove();
              return;
            }
            surface.classList.add("hidden");
            status.setAttribute("role", "alert");
            status.textContent = m.fileViewerFailed;
          },
          { compact: true },
        );
      };
      // Once the block is in the page (ProseMirror puts it there right after this returns).
      queueMicrotask(() => {
        if (gone) return;
        // ProseMirror makes a block that can be dragged draggable itself, and then a drag that starts in the document would move
        // the block instead of selecting its text. It is moved by the grip beside it (block-drag-handle.tsx), as any block is.
        dom.draggable = false;
        if (typeof IntersectionObserver === "undefined") {
          start();
          return;
        }
        watch = new IntersectionObserver(
          (entries) => {
            if (!entries.some((entry) => entry.isIntersecting)) return;
            watch?.disconnect();
            watch = null;
            start();
          },
          // The box that scrolls (a dialog, the page) is what hides the block, so it is the one to look ahead in.
          { root: scrollBoxOf(dom), rootMargin: "300px 0px" },
        );
        watch.observe(dom);
      });

      return {
        dom,
        update: (updated) => updated.type === node.type && updated.attrs.src === node.attrs.src,
        // What happens in the document is the viewer's own: selecting text, scrolling, zooming, a sheet's cells.
        stopEvent: (event) => event.target instanceof Element && body.contains(event.target),
        // The viewer draws into the block all the time; the block has no text of its own for these changes to be part of.
        ignoreMutation: () => true,
        destroy: () => {
          gone = true;
          watch?.disconnect();
          stop?.();
        },
      };
    };
  },
});
