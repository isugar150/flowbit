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

/** Tables with resizable columns and per-cell alignment. Node names must match RichText.kt. */
export const tableExtensions = [Table.configure({ resizable: true }), TableRow, AlignedHeader, AlignedCell];
