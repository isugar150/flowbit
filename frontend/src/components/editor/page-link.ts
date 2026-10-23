import { Node } from "@tiptap/core";

export type PageLinkStorage = {
  /** Current titles of the documents the blocks may point at, by id. */
  titles: Map<string, string>;
  /** Opens a document; set by the editor component. */
  open: (id: string) => void;
  /** The address a document block links to, for opening it in a new tab; `/doc/{id}` unless the editor component says otherwise. */
  href: (id: string) => string;
  /** Shown for a document without a title. */
  untitled: string;
  /** Redraws every block on screen, after `titles` changed. */
  redraw: Set<() => void>;
  /** What the editor component knows: how to open a document, and the documents' current titles. */
  configure: (options: { open: (id: string) => void; href?: (id: string) => string; untitled: string; documents: { id: string; title: string }[] }) => void;
};

declare module "@tiptap/core" {
  interface Storage {
    pageLink: PageLinkStorage;
  }
}

const ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/></svg>';

/**
 * A document placed in the text as a block of its own, the way a page sits inside a page: an
 * icon and the document's title, which opens it. The block only keeps the document's id; the
 * title in it is what to show until the current one is known (`storage.titles`).
 */
export const PageLink = Node.create<Record<string, never>, PageLinkStorage>({
  name: "pageLink",
  group: "block",
  atom: true,
  draggable: true,
  selectable: true,

  addStorage() {
    return {
      titles: new Map(),
      open: () => {},
      href: (id: string) => `/doc/${id}`,
      untitled: "",
      redraw: new Set(),
      // Written through `this`, not through a variable holding this object: the editor keeps its
      // own copy of the storage, and the blocks read that copy. Setting `open` on the object made
      // here left the copy with the empty default, so clicking a block did nothing.
      configure(this: PageLinkStorage, { open, href, untitled, documents }) {
        this.open = open;
        this.href = href ?? ((id: string) => `/doc/${id}`);
        this.untitled = untitled;
        for (const document of documents) this.titles.set(document.id, document.title || untitled);
        for (const redraw of this.redraw) redraw();
      },
    };
  },

  addAttributes() {
    return { id: { default: null }, title: { default: "" } };
  },

  parseHTML() {
    return [
      {
        tag: "a[data-page-link]",
        getAttrs: (element) => ({ id: element.getAttribute("data-page-link"), title: element.textContent ?? "" }),
      },
    ];
  },

  renderHTML({ node }) {
    return ["a", { "data-page-link": node.attrs.id, href: `/doc/${node.attrs.id}` }, node.attrs.title];
  },

  addNodeView() {
    return ({ node }) => {
      const storage = this.storage;
      const id = String(node.attrs.id);
      const dom = document.createElement("a");
      dom.href = `/doc/${id}`;
      dom.contentEditable = "false";
      dom.draggable = false;
      dom.setAttribute("data-page-link", id);
      dom.className =
        "flex h-9 cursor-pointer items-center gap-2 rounded-md px-2 text-sm font-medium text-foreground no-underline hover:bg-muted [&.ProseMirror-selectednode]:bg-muted [&>svg]:shrink-0 [&>svg]:text-muted-foreground";
      dom.innerHTML = ICON;
      const title = document.createElement("span");
      title.className = "truncate underline decoration-border underline-offset-4";
      dom.append(title);
      const draw = () => {
        dom.href = storage.href(id);
        title.textContent = storage.titles.get(id) ?? (String(node.attrs.title ?? "") || storage.untitled);
      };
      draw();
      storage.redraw.add(draw);
      dom.addEventListener("click", (event) => {
        // With a modifier key the browser opens the address in a new tab, as for any link.
        if (event.metaKey || event.ctrlKey || event.shiftKey) return;
        event.preventDefault();
        storage.open(id);
      });
      return {
        dom,
        update: (updated) => updated.type === node.type && updated.attrs.id === node.attrs.id,
        destroy: () => {
          storage.redraw.delete(draw);
        },
      };
    };
  },
});
