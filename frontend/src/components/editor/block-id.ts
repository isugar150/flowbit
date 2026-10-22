import { Extension } from "@tiptap/core";
import { Plugin, PluginKey } from "@tiptap/pm/state";

/** The blocks that can stand at the top of a document; each is given a number that does not change (D-165). */
const BLOCK_TYPES = [
  "paragraph",
  "heading",
  "bulletList",
  "orderedList",
  "taskList",
  "blockquote",
  "codeBlock",
  "horizontalRule",
  "image",
  "table",
  "pageLink",
  "embed",
  "fileBlock",
];

/** A random id; `crypto.randomUUID` only exists on secure pages, and a self-hosted site may be served over plain http. */
function newBlockId(): string {
  const random = globalThis.crypto;
  if (typeof random?.randomUUID === "function") return random.randomUUID();
  const bytes = new Uint8Array(16);
  if (random?.getRandomValues) random.getRandomValues(bytes);
  else for (let index = 0; index < bytes.length; index++) bytes[index] = Math.floor(Math.random() * 256);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * Gives every top-level block of the document a number (`attrs.blockId`) that stays with it when it is
 * moved or its text changes, so that a comment on the block stays with the block. The server does the
 * same when a document is saved; this is for the blocks made while it is being written (Enter splits
 * a block, and a pasted block can bring a number the document already has: the later one gets a new one).
 * The number is also written to the page as `data-block-id`, to find a block by it.
 *
 * Only an editor that can be changed gives numbers: a document opened to read is the server's.
 */
export const BlockId = Extension.create({
  name: "blockId",

  addGlobalAttributes() {
    return [
      {
        types: BLOCK_TYPES,
        attributes: {
          blockId: {
            default: null,
            // A block made by splitting another starts without one and gets its own below.
            keepOnSplit: false,
            parseHTML: (element: HTMLElement) => element.getAttribute("data-block-id"),
            renderHTML: (attributes: Record<string, unknown>) => (attributes.blockId ? { "data-block-id": String(attributes.blockId) } : {}),
          },
        },
      },
    ];
  },

  addProseMirrorPlugins() {
    const editor = this.editor;
    return [
      new Plugin({
        key: new PluginKey("blockId"),
        appendTransaction: (transactions, _before, state) => {
          if (!editor.isEditable || !transactions.some((transaction) => transaction.docChanged)) return null;
          const seen = new Set<string>();
          let tr = null as ReturnType<typeof state.tr.setNodeMarkup> | null;
          state.doc.forEach((node, offset) => {
            if (!BLOCK_TYPES.includes(node.type.name)) return;
            const current = node.attrs.blockId as string | null;
            if (current && !seen.has(current)) {
              seen.add(current);
              return;
            }
            const next = newBlockId();
            seen.add(next);
            tr = (tr ?? state.tr).setNodeMarkup(offset, undefined, { ...node.attrs, blockId: next });
          });
          return tr;
        },
      }),
    ];
  },
});
