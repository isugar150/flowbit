import { Extension } from "@tiptap/core";
import { Plugin, PluginKey } from "@tiptap/pm/state";
import { Decoration, DecorationSet } from "@tiptap/pm/view";
import { Table, TableCell, TableHeader, TableRow } from "@tiptap/extension-table";

export const CELL_ALIGNS = ["left", "center", "right"] as const;
export const CELL_VERTICAL_ALIGNS = ["top", "middle", "bottom"] as const;
export type CellAlign = (typeof CELL_ALIGNS)[number];
export type CellVerticalAlign = (typeof CELL_VERTICAL_ALIGNS)[number];

const oneOf = <T extends string>(allowed: readonly T[], value: unknown): T | null =>
  allowed.find((item) => item === value) ?? null;

/**
 * Alignment kept on each cell. Only the listed values are ever written into a style, so a
 * document cannot smuggle other CSS in through these attributes (the backend checks the same list).
 */
const cellAlignment = {
  align: {
    default: null,
    parseHTML: (element: HTMLElement) => oneOf(CELL_ALIGNS, element.style.textAlign),
    renderHTML: (attributes: Record<string, unknown>) => {
      const align = oneOf(CELL_ALIGNS, attributes.align);
      return align ? { style: `text-align: ${align}` } : {};
    },
  },
  verticalAlign: {
    default: null,
    parseHTML: (element: HTMLElement) => oneOf(CELL_VERTICAL_ALIGNS, element.style.verticalAlign),
    renderHTML: (attributes: Record<string, unknown>) => {
      const verticalAlign = oneOf(CELL_VERTICAL_ALIGNS, attributes.verticalAlign);
      return verticalAlign ? { style: `vertical-align: ${verticalAlign}` } : {};
    },
  },
};

const AlignedCell = TableCell.extend({
  addAttributes() {
    return { ...this.parent?.(), ...cellAlignment };
  },
});

const AlignedHeader = TableHeader.extend({
  addAttributes() {
    return { ...this.parent?.(), ...cellAlignment };
  },
});

/** The class a table has while its toolbar is shown above it (globals.css: only then does it keep room above itself). */
export const TOOLBAR_ROOM_CLASS = "table-has-toolbar";

/**
 * Marks the table the cursor is in while the toolbar is shown (table-toolbar.tsx shows it for an editor that can be
 * changed and has the focus, with the cursor in a table). Only that table keeps room above itself for the toolbar;
 * a table the cursor is not in lies where its text puts it. Focus and blur are transactions too, so the mark follows them.
 */
const TableToolbarRoom = Extension.create({
  name: "tableToolbarRoom",

  addProseMirrorPlugins() {
    const editor = this.editor;
    return [
      new Plugin({
        key: new PluginKey("tableToolbarRoom"),
        props: {
          decorations: (state) => {
            if (!editor.isEditable || !editor.isFocused) return null;
            const { $from } = state.selection;
            for (let depth = $from.depth; depth > 0; depth--) {
              const node = $from.node(depth);
              if (node.type.name !== "table") continue;
              const start = $from.before(depth);
              return DecorationSet.create(state.doc, [
                Decoration.node(start, start + node.nodeSize, { class: TOOLBAR_ROOM_CLASS }),
              ]);
            }
            return null;
          },
        },
      }),
    ];
  },
});

/** Tables with resizable columns and per-cell alignment. Node names must match RichText.kt. */
export const tableExtensions = [
  Table.configure({ resizable: true }),
  TableRow,
  AlignedHeader,
  AlignedCell,
  TableToolbarRoom,
];
