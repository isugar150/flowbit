"use client";

import { useEffect, useRef, useState } from "react";
import Image from "@tiptap/extension-image";
import { TaskItem, TaskList } from "@tiptap/extension-list";
import { Placeholder } from "@tiptap/extensions";
import { EditorContent, useEditor, type Editor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import {
  CodeIcon,
  FileTextIcon,
  Heading1Icon,
  Heading2Icon,
  Heading3Icon,
  ImageIcon,
  ListChecksIcon,
  ListIcon,
  ListOrderedIcon,
  MinusIcon,
  QuoteIcon,
  TableIcon,
  XIcon,
} from "lucide-react";
import { fileProblem, settingsErrorMessage } from "@/features/settings/error-message";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { BlockDragHandle } from "./block-drag-handle";
import { fixedOrigin } from "./fixed-origin";
import { tableExtensions } from "./table-extensions";
import { TableToolbar } from "./table-toolbar";
import { uploadEditorImage } from "./upload-image";

const m = messages.editor;

/** An editor document as stored by the backend (Tiptap JSON). */
export type RichDoc = Record<string, unknown>;

type SlashItem = {
  id: string;
  label: string;
  /** Extra words that find this block, so typing "/h2" or "/todo" works as well as the Korean name. */
  keywords: string;
  icon: React.ReactNode;
  run: (editor: Editor, range: { from: number; to: number }, actions: SlashActions) => void;
};

/** What a block needs from the editor component beyond the document itself. */
type SlashActions = { pickImage: () => void; createDocument: () => void };

const block = (editor: Editor, range: { from: number; to: number }) => editor.chain().focus().deleteRange(range);

/** Offered only where the editor was given a way to make documents (an issue's body). */
const DOCUMENT_ITEM = "document";

/** Blocks offered by the `/` menu. Only blocks the backend accepts (RichText.kt) may be listed here. */
const SLASH_ITEMS: SlashItem[] = [
  { id: "h1", label: m.heading1, keywords: "h1 heading 제목", icon: <Heading1Icon />, run: (e, r) => block(e, r).setHeading({ level: 1 }).run() },
  { id: "h2", label: m.heading2, keywords: "h2 heading 제목", icon: <Heading2Icon />, run: (e, r) => block(e, r).setHeading({ level: 2 }).run() },
  { id: "h3", label: m.heading3, keywords: "h3 heading 제목", icon: <Heading3Icon />, run: (e, r) => block(e, r).setHeading({ level: 3 }).run() },
  { id: "bullet", label: m.bulletList, keywords: "ul bullet list 목록", icon: <ListIcon />, run: (e, r) => block(e, r).toggleBulletList().run() },
  { id: "ordered", label: m.orderedList, keywords: "ol number list 번호 목록", icon: <ListOrderedIcon />, run: (e, r) => block(e, r).toggleOrderedList().run() },
  { id: "task", label: m.taskList, keywords: "todo task check 할 일 체크", icon: <ListChecksIcon />, run: (e, r) => block(e, r).toggleTaskList().run() },
  { id: "code", label: m.codeBlock, keywords: "code 코드", icon: <CodeIcon />, run: (e, r) => block(e, r).setCodeBlock().run() },
  { id: "quote", label: m.blockquote, keywords: "quote 인용", icon: <QuoteIcon />, run: (e, r) => block(e, r).setBlockquote().run() },
  {
    id: "table",
    label: m.table,
    keywords: "table grid 표 테이블",
    icon: <TableIcon />,
    run: (e, r) => block(e, r).insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(),
  },
  {
    id: "image",
    label: m.image,
    keywords: "image picture photo img 이미지 사진 그림",
    icon: <ImageIcon />,
    run: (e, r, actions) => {
      block(e, r).run();
      actions.pickImage();
    },
  },
  {
    id: DOCUMENT_ITEM,
    label: m.document,
    keywords: "document doc page 문서 페이지",
    icon: <FileTextIcon />,
    run: (e, r, actions) => {
      block(e, r).run();
      actions.createDocument();
    },
  },
  { id: "rule", label: m.divider, keywords: "hr divider line 구분선", icon: <MinusIcon />, run: (e, r) => block(e, r).setHorizontalRule().run() },
];

/** Compares two documents by content; key order differs between the editor and the database (jsonb). */
function sameDoc(a: RichDoc | null, b: RichDoc | null): boolean {
  return stableJson(a) === stableJson(b);
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, item]) => item !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${stableJson(item)}`).join(",")}}`;
  }
  return JSON.stringify(value ?? null);
}

type SlashState = { from: number; to: number; query: string; top: number; left: number };

const MENU_HEIGHT = 320;

const imageFiles = (list: FileList | null | undefined): File[] =>
  Array.from(list ?? []).filter((file) => file.type.startsWith("image/"));

function matchingItems(query: string, withDocuments: boolean): SlashItem[] {
  const needle = query.toLowerCase();
  return SLASH_ITEMS.filter(
    (item) =>
      (withDocuments || item.id !== DOCUMENT_ITEM) && `${item.label} ${item.keywords}`.toLowerCase().includes(needle),
  );
}

/** Where the `/` menu should be, if the cursor sits right after "/word" in an ordinary paragraph. */
function readSlash(editor: Editor, probe: HTMLElement | null): SlashState | null {
  const { selection } = editor.state;
  const { $from } = selection;
  if (!selection.empty || $from.parent.type.name !== "paragraph") return null;
  const before = $from.parent.textBetween(0, $from.parentOffset, undefined, "￼");
  const match = /(?:^|\s)\/([^\s/]*)$/.exec(before);
  if (!match) return null;
  const coords = editor.view.coordsAtPos($from.pos);
  // Open below the line; flip above it when there is no room left under the cursor.
  const top = coords.bottom + MENU_HEIGHT + 8 > window.innerHeight ? Math.max(8, coords.top - MENU_HEIGHT - 6) : coords.bottom + 6;
  const origin = fixedOrigin(probe);
  return {
    from: $from.pos - match[1].length - 1,
    to: $from.pos,
    query: match[1],
    top: top - origin.y,
    left: Math.max(8, coords.left) - origin.x,
  };
}

/**
 * The document editor used for project overviews and issue bodies.
 *
 * Type `/` for a menu of blocks; markdown shortcuts also work (`## `, `- `, `1. `, `[] `, `> `).
 * Pictures can be chosen from the menu, pasted or dropped in; they are uploaded first and the
 * document only keeps their address. With the cursor in a table, a toolbar for it appears. A grip
 * to the left of the block under the pointer drags that block to another place.
 * Content goes in and out as JSON, never HTML. To replace the content from outside (for example
 * when a form is cleared), give the component a new `key` so it starts again.
 *
 * When `initialContent` changes afterwards (someone else saved, and the page was refreshed by
 * realtime), the new document is shown unless `keepLocal` says there is typing not yet saved here.
 */
export function RichTextEditor({
  initialContent,
  onChange,
  editable = true,
  placeholder,
  ariaLabel,
  className,
  autoFocus,
  keepLocal,
  onCreateDocument,
}: {
  initialContent: RichDoc | null;
  /**
   * Makes a new document and says where it is. When given, the `/` menu offers "문서", which puts
   * a link to the new document at the cursor.
   */
  onCreateDocument?: () => Promise<{ href: string; title: string }>;
  /** True while this editor has edits the server has not stored yet. */
  keepLocal?: () => boolean;
  /** Called with the document after each edit, or null when it has been emptied. */
  onChange?: (content: RichDoc | null) => void;
  editable?: boolean;
  placeholder?: string;
  ariaLabel: string;
  className?: string;
  autoFocus?: boolean;
}) {
  const [slash, setSlash] = useState<SlashState | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [probe, setProbe] = useState<HTMLSpanElement | null>(null);
  const probeRef = useRef<HTMLSpanElement | null>(null);
  const [uploading, setUploading] = useState(0);
  const [imageError, setImageError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const editorRef = useRef<Editor | null>(null);
  // The key handler below is created once, so it reads the latest menu state through refs.
  const slashRef = useRef<SlashState | null>(null);
  const indexRef = useRef(0);
  const dismissedAtRef = useRef<number | null>(null);
  const interactedRef = useRef(false);
  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  const updateSlash = (editor: Editor) => {
    const next = editor.isEditable ? readSlash(editor, probeRef.current) : null;
    // After Escape the menu stays closed until the "/" it belonged to is gone.
    const visible = next && dismissedAtRef.current === next.from ? null : next;
    if (!next) dismissedAtRef.current = null;
    if (visible?.query !== slashRef.current?.query) {
      indexRef.current = 0;
      setActiveIndex(0);
    }
    slashRef.current = visible;
    setSlash(visible);
  };

  /** Uploads the pictures one by one and places each at `position` (the cursor when not given). */
  const insertImages = async (files: File[], position?: number) => {
    const current = editorRef.current;
    if (!current || files.length === 0) return;
    interactedRef.current = true;
    setImageError(null);
    let at = position;
    for (const file of files) {
      const problem = fileProblem(file);
      if (problem) {
        setImageError(problem);
        continue;
      }
      setUploading((count) => count + 1);
      try {
        const src = await uploadEditorImage(file);
        if (current.isDestroyed) return;
        const image = { type: "image", attrs: { src, alt: file.name } };
        if (at === undefined) {
          current.chain().focus().insertContent(image).run();
        } else {
          at = Math.min(at, current.state.doc.content.size);
          current.chain().focus().insertContentAt(at, image).run();
          at += 1;
        }
      } catch (error) {
        setImageError(`${m.imageFailed} ${settingsErrorMessage(error)}`);
      } finally {
        setUploading((count) => count - 1);
      }
    }
  };
  const insertImagesRef = useRef(insertImages);
  useEffect(() => {
    insertImagesRef.current = insertImages;
  });
  const createDocumentRef = useRef(onCreateDocument);
  useEffect(() => {
    createDocumentRef.current = onCreateDocument;
  }, [onCreateDocument]);
  const insertDocumentLink = async () => {
    const current = editorRef.current;
    const create = createDocumentRef.current;
    if (!current || !create) return;
    interactedRef.current = true;
    setImageError(null);
    try {
      const { href, title } = await create();
      if (current.isDestroyed) return;
      current
        .chain()
        .focus()
        .insertContent([
          { type: "text", text: title, marks: [{ type: "link", attrs: { href } }] },
          { type: "text", text: " " },
        ])
        .run();
    } catch (error) {
      setImageError(settingsErrorMessage(error));
    }
  };
  const insertDocumentLinkRef = useRef(insertDocumentLink);
  useEffect(() => {
    insertDocumentLinkRef.current = insertDocumentLink;
  });
  const slashActions: SlashActions = {
    pickImage: () => fileInputRef.current?.click(),
    createDocument: () => void insertDocumentLinkRef.current(),
  };

  const editor = useEditor({
    // Rendered on the server first; the editor attaches in the browser.
    immediatelyRender: false,
    editable,
    autofocus: autoFocus ? "end" : false,
    content: initialContent ?? undefined,
    extensions: [
      StarterKit.configure({ link: { openOnClick: false, autolink: true, defaultProtocol: "https" } }),
      TaskList,
      TaskItem.configure({ nested: true }),
      Placeholder.configure({ placeholder: placeholder ?? "" }),
      Image.configure({ allowBase64: false }),
      ...tableExtensions,
    ],
    editorProps: {
      attributes: { "aria-label": ariaLabel, role: "textbox", "aria-multiline": "true" },
      handlePaste: (_view, event) => {
        const files = imageFiles(event.clipboardData?.files);
        if (files.length === 0) return false;
        void insertImagesRef.current(files);
        return true;
      },
      handleDrop: (view, event, _slice, moved) => {
        // `moved` is a block dragged within the document, which the editor handles itself.
        const files = moved ? [] : imageFiles(event.dataTransfer?.files);
        if (files.length === 0) return false;
        event.preventDefault();
        const position = view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos;
        void insertImagesRef.current(files, position);
        return true;
      },
      handleKeyDown: (view, event) => {
        const state = slashRef.current;
        if (!state || event.isComposing) return false;
        const items = matchingItems(state.query, Boolean(createDocumentRef.current));
        if (event.key === "Escape") {
          dismissedAtRef.current = state.from;
          slashRef.current = null;
          setSlash(null);
          return true;
        }
        if (items.length === 0) return false;
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          const step = event.key === "ArrowDown" ? 1 : -1;
          indexRef.current = (indexRef.current + step + items.length) % items.length;
          setActiveIndex(indexRef.current);
          return true;
        }
        if (event.key === "Enter" || event.key === "Tab") {
          const item = items[Math.min(indexRef.current, items.length - 1)];
          const current = editorRef.current;
          if (current) {
            item.run(
              current,
              { from: state.from, to: state.to },
              { pickImage: () => fileInputRef.current?.click(), createDocument: () => void insertDocumentLinkRef.current() },
            );
          }
          return true;
        }
        return false;
      },
    },
    onUpdate: ({ editor: current }) => {
      updateSlash(current);
      // The editor also updates itself while starting up (it normalises the document). Only
      // changes made after the person touched it count as edits worth saving.
      if (!interactedRef.current) return;
      onChangeRef.current?.(current.isEmpty ? null : (current.getJSON() as RichDoc));
    },
    onSelectionUpdate: ({ editor: current }) => updateSlash(current),
    onBlur: () => {
      slashRef.current = null;
      setSlash(null);
    },
  });
  useEffect(() => {
    editorRef.current = editor;
  }, [editor]);

  useEffect(() => {
    editor?.setEditable(editable);
  }, [editor, editable]);

  const keepLocalRef = useRef(keepLocal);
  useEffect(() => {
    keepLocalRef.current = keepLocal;
  }, [keepLocal]);

  useEffect(() => {
    if (!editor || editor.isDestroyed || keepLocalRef.current?.()) return;
    const shown = editor.isEmpty ? null : (editor.getJSON() as RichDoc);
    if (sameDoc(shown, initialContent)) return;
    const { from, to } = editor.state.selection;
    if (initialContent) editor.commands.setContent(initialContent, { emitUpdate: false });
    else editor.commands.clearContent(false);
    if (editor.isFocused) {
      // Keep the cursor about where it was rather than jumping to the end.
      const max = editor.state.doc.content.size;
      editor.commands.setTextSelection({ from: Math.min(from, max), to: Math.min(to, max) });
    }
  }, [editor, initialContent]);

  const items = slash ? matchingItems(slash.query, Boolean(onCreateDocument)) : [];

  return (
    <div
      className={cn("rich-text relative", className)}
      onPointerDownCapture={() => {
        interactedRef.current = true;
      }}
      onKeyDownCapture={() => {
        interactedRef.current = true;
      }}
      onPasteCapture={() => {
        interactedRef.current = true;
      }}
      onDropCapture={() => {
        interactedRef.current = true;
      }}
    >
      <EditorContent editor={editor} />
      {/* Marks the origin of fixed positioning for the menu and the table toolbar (see fixedOrigin). */}
      <span
        ref={(node) => {
          probeRef.current = node;
          setProbe(node);
        }}
        aria-hidden="true"
        className="pointer-events-none fixed top-0 left-0 size-0"
      />
      {editor && editable ? <TableToolbar editor={editor} probe={probe} /> : null}
      {editor && editable ? <BlockDragHandle editor={editor} container={probe?.parentElement ?? null} /> : null}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/gif"
        multiple
        hidden
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => {
          const files = imageFiles(event.target.files);
          event.target.value = "";
          void insertImages(files);
        }}
      />
      {uploading > 0 ? (
        <p role="status" className="mt-2 text-[0.8125rem] leading-5 text-muted-foreground">
          {m.imageUploading}
        </p>
      ) : null}
      {imageError ? (
        <p role="alert" className="mt-2 flex items-start gap-1 text-[0.8125rem] leading-5 text-destructive">
          <span className="min-w-0 flex-1">{imageError}</span>
          <button
            type="button"
            aria-label={m.dismiss}
            onClick={() => setImageError(null)}
            className="flex size-5 shrink-0 items-center justify-center rounded hover:bg-destructive/10 [&>svg]:size-3.5"
          >
            <XIcon />
          </button>
        </p>
      ) : null}
      {slash && editor && items.length > 0 ? (
        <div
          role="listbox"
          aria-label={m.slashMenu}
          style={{ top: slash.top, left: slash.left, maxHeight: MENU_HEIGHT }}
          className="fixed z-50 flex w-56 flex-col overflow-y-auto overscroll-contain rounded-lg bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10"
        >
          {items.map((item, index) => (
            <button
              key={item.id}
              type="button"
              role="option"
              aria-selected={index === activeIndex}
              // Keep the cursor in the document: a click must not move focus to the menu.
              onMouseDown={(event) => {
                event.preventDefault();
                item.run(editor, { from: slash.from, to: slash.to }, slashActions);
              }}
              onMouseEnter={() => {
                indexRef.current = index;
                setActiveIndex(index);
              }}
              className={cn(
                "flex h-8 items-center gap-2 rounded-md px-2 text-left text-[0.8125rem] leading-5 [&>svg]:size-4 [&>svg]:text-muted-foreground",
                index === activeIndex && "bg-accent text-accent-foreground",
              )}
            >
              {item.icon}
              {item.label}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
