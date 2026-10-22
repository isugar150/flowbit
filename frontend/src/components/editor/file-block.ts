import { Node } from "@tiptap/core";
import { messages } from "@/messages";

const m = messages.editor;

/** Where an uploaded file is served from; the same pattern as `STORED_FILE` in RichText.kt. */
const STORED_FILE = /^\/api\/v1\/editor-files\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** The address that downloads the file under its own name. */
export const fileBlockHref = (src: string, name: string) => `${src}?name=${encodeURIComponent(name)}`;

const FILE_ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/></svg>';
const DOWNLOAD_ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 15V3"/><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5"/></svg>';

/**
 * A file placed in the text as a block of its own (D-182): an icon, the name and the size, which download it. It can be
 * dragged like any block. The block keeps the file's address, name and size; the file itself is on the server.
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
      const dom = document.createElement("div");
      dom.contentEditable = "false";
      dom.setAttribute("data-file-block", src);
      dom.className = "rich-text-file my-2 [&.ProseMirror-selectednode]:ring-2 [&.ProseMirror-selectednode]:ring-ring";

      const link = document.createElement("a");
      link.href = STORED_FILE.test(src) ? fileBlockHref(src, name) : "#";
      link.download = name;
      link.draggable = false;
      link.title = m.fileDownload(name);
      link.setAttribute("aria-label", m.fileDownload(name));
      link.className =
        "flex min-h-12 max-w-full items-center gap-3 rounded-lg border border-border bg-card px-3 py-2 text-foreground no-underline outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50";
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
      const download = document.createElement("span");
      download.className = "flex shrink-0 text-muted-foreground";
      download.innerHTML = DOWNLOAD_ICON;
      link.append(icon, text, download);
      dom.append(link);
      return { dom, update: (updated) => updated.type === node.type && updated.attrs.src === node.attrs.src };
    };
  },
});
