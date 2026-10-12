"use client";

import { useEffect, useState } from "react";
import { useEditorState, type Editor } from "@tiptap/react";
import {
  AlignVerticalJustifyCenterIcon,
  AlignVerticalJustifyEndIcon,
  AlignVerticalJustifyStartIcon,
  BetweenHorizontalEndIcon,
  BetweenHorizontalStartIcon,
  BetweenVerticalEndIcon,
  BetweenVerticalStartIcon,
  Columns3Icon,
  PanelLeftIcon,
  PanelTopIcon,
  Rows3Icon,
  TableCellsMergeIcon,
  TableCellsSplitIcon,
  TextAlignCenterIcon,
  TextAlignEndIcon,
  TextAlignStartIcon,
  Trash2Icon,
  XIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { fixedOrigin } from "./fixed-origin";
import type { CellAlign, CellVerticalAlign } from "./table-extensions";

const m = messages.editor.tableTools;

/** Room the toolbar takes above the table, including the gap. */
const TOOLBAR_SPACE = 44;

type Place = { top: number; left: number; maxWidth: number };

/**
 * Where to float the toolbar for the table the cursor is in: just above the table, or pinned near
 * the top of the window while a long table is scrolled. Null when there is no such table on screen.
 */
function toolbarPlace(editor: Editor, probe: HTMLElement | null): Place | null {
  // Only while the cursor is really in the table; a document that merely starts with one does not count.
  if (!probe || !editor.isEditable || !editor.isFocused || !editor.isActive("table")) return null;
  const { node } = editor.view.domAtPos(editor.state.selection.$from.pos);
  const element = node instanceof Element ? node : node.parentElement;
  const table = element?.closest("table");
  if (!table) return null;
  const rect = table.getBoundingClientRect();
  if (rect.bottom < TOOLBAR_SPACE || rect.top > window.innerHeight) return null;
  // Fixed positioning, so the editor's own scroll box (as in the new issue window) cannot clip it.
  const origin = fixedOrigin(probe);
  const left = Math.max(8, rect.left);
  return {
    top: Math.round(Math.max(8, rect.top - TOOLBAR_SPACE) - origin.y),
    left: Math.round(left - origin.x),
    maxWidth: Math.round(window.innerWidth - left - 8),
  };
}

function ToolButton({
  label,
  active,
  disabled,
  destructive,
  onRun,
  children,
}: {
  label: string;
  active?: boolean;
  disabled?: boolean;
  destructive?: boolean;
  onRun: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={active}
      disabled={disabled}
      // Keep the selection in the table: a click must not move focus to the toolbar.
      onMouseDown={(event) => event.preventDefault()}
      onClick={onRun}
      className={cn(
        "flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-accent hover:text-accent-foreground focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-40 max-md:size-9 [&>svg]:size-4",
        active && "bg-accent text-accent-foreground",
        destructive && "hover:bg-destructive/10 hover:text-destructive",
      )}
    >
      {children}
    </button>
  );
}

const Divider = () => <span aria-hidden="true" className="mx-0.5 h-4 w-px shrink-0 bg-border" />;

/**
 * Buttons for the table the cursor is in: rows and columns, merging, alignment. Floats above the
 * table and stays in view while a long table is scrolled.
 */
export function TableToolbar({ editor, probe }: { editor: Editor; probe: HTMLElement | null }) {
  const [place, setPlace] = useState<Place | null>(null);
  const state = useEditorState({
    editor,
    selector: ({ editor: current }) => {
      const cell = current.isActive("tableHeader") ? "tableHeader" : "tableCell";
      const attributes = current.getAttributes(cell);
      return {
        canMerge: current.can().mergeCells(),
        canSplit: current.can().splitCell(),
        align: (attributes.align ?? null) as CellAlign | null,
        verticalAlign: (attributes.verticalAlign ?? null) as CellVerticalAlign | null,
      };
    },
  });

  useEffect(() => {
    const measure = () => {
      const next = toolbarPlace(editor, probe);
      setPlace((previous) =>
        previous?.top === next?.top && previous?.left === next?.left && previous?.maxWidth === next?.maxWidth
          ? previous
          : next,
      );
    };
    const hide = () => setPlace(null);
    measure();
    editor.on("transaction", measure);
    editor.on("focus", measure);
    editor.on("blur", hide);
    window.addEventListener("resize", measure);
    // Capture: the page, the editor and a dialog each have their own scroll box.
    document.addEventListener("scroll", measure, true);
    return () => {
      editor.off("transaction", measure);
      editor.off("focus", measure);
      editor.off("blur", hide);
      window.removeEventListener("resize", measure);
      document.removeEventListener("scroll", measure, true);
    };
  }, [editor, probe]);

  if (!place) return null;

  const run = () => editor.chain().focus();
  // Choosing the alignment a cell already has clears it.
  const setAlign = (value: CellAlign) =>
    run()
      .setCellAttribute("align", state.align === value ? null : value)
      .run();
  const setVerticalAlign = (value: CellVerticalAlign) =>
    run()
      .setCellAttribute("verticalAlign", state.verticalAlign === value ? null : value)
      .run();

  return (
    <div
      role="toolbar"
      aria-label={m.toolbar}
      style={place}
      className="fixed z-50 flex w-fit flex-wrap items-center gap-0.5 rounded-lg bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10"
    >
      <ToolButton label={m.addRowBefore} onRun={() => run().addRowBefore().run()}>
        <BetweenHorizontalEndIcon />
      </ToolButton>
      <ToolButton label={m.addRowAfter} onRun={() => run().addRowAfter().run()}>
        <BetweenHorizontalStartIcon />
      </ToolButton>
      <ToolButton label={m.deleteRow} destructive onRun={() => run().deleteRow().run()}>
        <span className="relative flex">
          <Rows3Icon className="size-4" />
          <XIcon className="absolute -right-1.5 -bottom-1.5 size-2.5 rounded-full bg-popover" strokeWidth={3} />
        </span>
      </ToolButton>
      <Divider />
      <ToolButton label={m.addColumnBefore} onRun={() => run().addColumnBefore().run()}>
        <BetweenVerticalEndIcon />
      </ToolButton>
      <ToolButton label={m.addColumnAfter} onRun={() => run().addColumnAfter().run()}>
        <BetweenVerticalStartIcon />
      </ToolButton>
      <ToolButton label={m.deleteColumn} destructive onRun={() => run().deleteColumn().run()}>
        <span className="relative flex">
          <Columns3Icon className="size-4" />
          <XIcon className="absolute -right-1.5 -bottom-1.5 size-2.5 rounded-full bg-popover" strokeWidth={3} />
        </span>
      </ToolButton>
      <Divider />
      <ToolButton label={m.mergeCells} disabled={!state.canMerge} onRun={() => run().mergeCells().run()}>
        <TableCellsMergeIcon />
      </ToolButton>
      <ToolButton label={m.splitCell} disabled={!state.canSplit} onRun={() => run().splitCell().run()}>
        <TableCellsSplitIcon />
      </ToolButton>
      <Divider />
      <ToolButton label={m.alignLeft} active={state.align === "left"} onRun={() => setAlign("left")}>
        <TextAlignStartIcon />
      </ToolButton>
      <ToolButton label={m.alignCenter} active={state.align === "center"} onRun={() => setAlign("center")}>
        <TextAlignCenterIcon />
      </ToolButton>
      <ToolButton label={m.alignRight} active={state.align === "right"} onRun={() => setAlign("right")}>
        <TextAlignEndIcon />
      </ToolButton>
      <Divider />
      <ToolButton label={m.alignTop} active={state.verticalAlign === "top"} onRun={() => setVerticalAlign("top")}>
        <AlignVerticalJustifyStartIcon />
      </ToolButton>
      <ToolButton
        label={m.alignMiddle}
        active={state.verticalAlign === "middle"}
        onRun={() => setVerticalAlign("middle")}
      >
        <AlignVerticalJustifyCenterIcon />
      </ToolButton>
      <ToolButton
        label={m.alignBottom}
        active={state.verticalAlign === "bottom"}
        onRun={() => setVerticalAlign("bottom")}
      >
        <AlignVerticalJustifyEndIcon />
      </ToolButton>
      <Divider />
      <ToolButton label={m.toggleHeaderRow} onRun={() => run().toggleHeaderRow().run()}>
        <PanelTopIcon />
      </ToolButton>
      <ToolButton label={m.toggleHeaderColumn} onRun={() => run().toggleHeaderColumn().run()}>
        <PanelLeftIcon />
      </ToolButton>
      <Divider />
      <ToolButton label={m.deleteTable} destructive onRun={() => run().deleteTable().run()}>
        <Trash2Icon />
      </ToolButton>
    </div>
  );
}
