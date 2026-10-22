"use client";

import { useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Image from "@tiptap/extension-image";
import { TaskItem, TaskList } from "@tiptap/extension-list";
import { Placeholder } from "@tiptap/extensions";
import { EditorContent, useEditor, type Editor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import {
  AtSignIcon,
  CodeIcon,
  FrameIcon,
  FileTextIcon,
  Heading1Icon,
  Heading2Icon,
  Heading3Icon,
  ImageIcon,
  ListChecksIcon,
  ListIcon,
  ListOrderedIcon,
  MinusIcon,
  PaperclipIcon,
  QuoteIcon,
  TableIcon,
  XIcon,
} from "lucide-react";
import { ACCEPTED_IMAGE_TYPES, fileProblem, settingsErrorMessage } from "@/features/settings/error-message";
import { useUploadLimit } from "@/features/settings/upload-limit-provider";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { BlockDragHandle } from "./block-drag-handle";
import { BlockId } from "./block-id";
import { Embed, MAX_EMBED_URL_LENGTH, embedFor } from "./embed";
import { FileBlock } from "./file-block";
import { fixedOrigin } from "./fixed-origin";
import { ImageViewer, type ViewerImage } from "./image-viewer";
import { LinkCard, cardPlace, clickedLink, openLink, pressOn, type LinkCardState, type Press } from "./link-card";
import { Mention, MentionPeopleContext, type MentionPerson } from "./mention";
import { PageLink } from "./page-link";
import { tableExtensions } from "./table-extensions";
import { TableToolbar } from "./table-toolbar";
import { uploadEditorFile, uploadEditorImage } from "./upload-image";

const m = messages.editor;

/** An editor document as stored by the backend (Tiptap JSON). */
export type RichDoc = Record<string, unknown>;

/** What an overlay is given to lay itself over an editor. */
export type EditorOverlayContext = { editor: Editor; container: HTMLElement | null };

/** What a parent can ask of an editor from outside. */
export type RichTextEditorHandle = {
  /** Uploads the files and puts them at the cursor, the same as pasting them in: pictures as pictures, every other file as a file block. */
  insertFiles: (files: File[]) => Promise<void>;
};

type SlashItem = {
  id: string;
  label: string;
  /** Extra words that find this block, so typing "/h2" or "/todo" works as well as the Korean name. */
  keywords: string;
  /** Shown after the label in muted text: what tells two people of the same name apart. */
  hint?: string;
  icon: React.ReactNode;
  run: (editor: Editor, range: { from: number; to: number }, actions: SlashActions) => void;
};

/** What a block needs from the editor component beyond the document itself. */
type SlashActions = { pickImage: () => void; pickFile: () => void; createDocument: () => void; addEmbed: () => void };

const block = (editor: Editor, range: { from: number; to: number }) => editor.chain().focus().deleteRange(range);

/** Offered only where the editor was given a way to make documents. */
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
    id: "file",
    label: m.file,
    hint: m.fileHint,
    keywords: "file attach attachment upload 파일 첨부 업로드",
    icon: <PaperclipIcon />,
    run: (e, r, actions) => {
      block(e, r).run();
      actions.pickFile();
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
  {
    id: "embed",
    label: m.embed,
    hint: m.embedHint,
    keywords:
      "embed iframe figma outline google docs sheets slides office word excel powerpoint sharepoint onedrive 임베드 피그마 아웃라인 구글 문서 시트 스프레드시트 슬라이드 프레젠테이션 오피스 워드 엑셀 파워포인트",
    icon: <FrameIcon />,
    run: (e, r, actions) => {
      block(e, r).run();
      actions.addEmbed();
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

/** The menu at the cursor: blocks after "/", people after "@". */
type SlashState = {
  kind: "slash" | "mention";
  from: number;
  to: number;
  query: string;
  /** The edge the menu hangs from: its top, or its bottom when `above`. */
  top: number;
  left: number;
  /** Width left between the menu's left edge and the right edge of the editor. */
  room: number;
  /** No room under the cursor: the menu grows upwards from the line instead. */
  above: boolean;
  maxHeight: number;
};

const MAX_MENTION_ITEMS = 8;

const MENU_HEIGHT = 320;

/** The width of the block menu (w-56), and the least the people menu is ever squeezed to. */
const MENU_MIN_WIDTH = 224;

const isPicture = (file: File) => ACCEPTED_IMAGE_TYPES.includes(file.type);

/** Everything that was dropped or pasted as a file; a picture becomes a picture, any other file a file block (D-182). */
const droppedFiles = (list: FileList | null | undefined): File[] => Array.from(list ?? []);

function matchingItems(query: string, withDocuments: boolean): SlashItem[] {
  const needle = query.toLowerCase();
  return SLASH_ITEMS.filter(
    (item) =>
      (withDocuments || item.id !== DOCUMENT_ITEM) && `${item.label} ${item.keywords}`.toLowerCase().includes(needle),
  );
}

/** What the menu offers: the blocks matching "/word", or the people matching "@name". */
function menuItems(
  state: Pick<SlashState, "kind" | "query">,
  withDocuments: boolean,
  people: MentionPerson[],
): SlashItem[] {
  if (state.kind === "slash") return matchingItems(state.query, withDocuments);
  const needle = state.query.toLowerCase();
  return people
    .filter((person) => `${person.name} ${person.loginId}`.toLowerCase().includes(needle))
    .slice(0, MAX_MENTION_ITEMS)
    .map((person) => ({
      id: person.id,
      label: person.name,
      keywords: person.loginId,
      hint: person.name === person.loginId ? undefined : person.loginId,
      icon: <AtSignIcon />,
      run: (editor, range) =>
        editor
          .chain()
          .focus()
          .insertContentAt(range, [
            { type: "mention", attrs: { id: person.id, label: person.name } },
            { type: "text", text: " " },
          ])
          .run(),
    }));
}

/**
 * Where the menu should be, if the cursor sits right after "/word" in an ordinary paragraph, or,
 * where people can be tagged, right after "@name".
 */
function readSlash(editor: Editor, probe: HTMLElement | null, withMentions: boolean, withSlash: boolean): SlashState | null {
  const { selection } = editor.state;
  const { $from } = selection;
  if (!selection.empty) return null;
  const parent = $from.parent.type.name;
  const before = $from.parent.textBetween(0, $from.parentOffset, undefined, "￼");
  const slashMatch = withSlash && parent === "paragraph" ?/(?:^|\s)\/([^\s/]*)$/.exec(before) : null;
  const match = slashMatch ?? (withMentions && parent !== "codeBlock" ? /(?:^|\s)@([^\s@]*)$/.exec(before) : null);
  if (!match) return null;
  const coords = editor.view.coordsAtPos($from.pos);
  // Open below the line; flip above it when there is no room left under the cursor. Above, the
  // menu is placed by its bottom edge, so a short list (two people) still sits next to the line.
  const above = coords.bottom + MENU_HEIGHT + 8 > window.innerHeight;
  const top = above ? coords.top - 6 : coords.bottom + 6;
  const origin = fixedOrigin(probe);
  return {
    kind: slashMatch ? "slash" : "mention",
    from: $from.pos - match[1].length - 1,
    to: $from.pos,
    query: match[1],
    top: top - origin.y,
    left: Math.max(8, coords.left) - origin.x,
    // How far the menu may grow to the right: to the edge of the editor, and never past the window.
    room: Math.min(window.innerWidth - 8, editor.view.dom.getBoundingClientRect().right) - Math.max(8, coords.left),
    above,
    maxHeight: above ? Math.max(120, Math.min(MENU_HEIGHT, top - 8)) : MENU_HEIGHT,
  };
}

/**
 * The document editor used for project overviews and issue bodies.
 *
 * Type `/` for a menu of blocks; markdown shortcuts also work (`## `, `- `, `1. `, `[] `, `> `).
 * Pictures can be chosen from the menu, pasted or dropped in; they are uploaded first and the
 * document only keeps their address. With the cursor in a table, a toolbar for it appears. A grip
 * to the left of the block under the pointer drags that block to another place. Where `mentionables`
 * is given, typing `@` offers those people and tags the chosen one.
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
  documents,
  mentionables,
  ref,
  blockIds = false,
  plain = false,
  overlay,
}: {
  initialContent: RichDoc | null;
  ref?: React.Ref<RichTextEditorHandle>;
  /** Gives every top-level block a number that does not change, so that comments can stay with their blocks (D-165). */
  blockIds?: boolean;
  /** Only text and tags: no blocks, no pictures, no `/` menu. For a comment on a block. */
  plain?: boolean;
  /** What to lay over the editor (the comments of its blocks); it is given the editor and the box it sits in. */
  overlay?: (context: EditorOverlayContext) => React.ReactNode;
  /**
   * Makes a new document with this title and says which. When given, the `/` menu offers "문서":
   * it asks for the title first, then puts the new document at the cursor as a block of its own.
   * Without a title nothing is made.
   */
  onCreateDocument?: (title: string) => Promise<{ id: string; title: string }>;
  /** The documents the text may hold as blocks, for their current titles. */
  documents?: { id: string; title: string }[];
  /** The people `@` offers to tag. Without them `@` is ordinary text. */
  mentionables?: MentionPerson[];
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
  const uploadLimit = useUploadLimit();
  // A link that was clicked shows where it goes; a second click on it opens it in a new tab (D-175).
  const boxRef = useRef<HTMLDivElement>(null);
  const pressRef = useRef<Press | null>(null);
  const [linkCard, setLinkCard] = useState<LinkCardState | null>(null);
  const closeLinkCard = useCallback(() => setLinkCard(null), []);
  const [slash, setSlash] = useState<SlashState | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [probe, setProbe] = useState<HTMLSpanElement | null>(null);
  const probeRef = useRef<HTMLSpanElement | null>(null);
  const [uploading, setUploading] = useState(0);
  const [imageError, setImageError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const anyFileInputRef = useRef<HTMLInputElement>(null);
  // The picture of the text that was clicked, enlarged over the page (D-182).
  const [viewer, setViewer] = useState<{ images: ViewerImage[]; index: number } | null>(null);
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

  // Tags in the text look their person up here, for the name on the chip and the card behind it.
  const mentionPeople = useMemo(() => new Map((mentionables ?? []).map((person) => [person.id, person])), [mentionables]);
  const mentionablesRef = useRef(mentionables);
  useEffect(() => {
    mentionablesRef.current = mentionables;
  }, [mentionables]);

  const updateSlash = (editor: Editor) => {
    const next = editor.isEditable ? readSlash(editor, probeRef.current, Boolean(mentionablesRef.current?.length), !plain) : null;
    // After Escape the menu stays closed until the "/" it belonged to is gone.
    const visible = next && dismissedAtRef.current === next.from ? null : next;
    if (!next) dismissedAtRef.current = null;
    if (visible?.query !== slashRef.current?.query || visible?.kind !== slashRef.current?.kind) {
      indexRef.current = 0;
      setActiveIndex(0);
    }
    slashRef.current = visible;
    setSlash(visible);
  };

  /**
   * Uploads the files one by one and places each at `position` (the cursor when not given): a picture as a picture, any
   * other file as a block that shows its name and size.
   */
  const insertFiles = async (files: File[], position?: number) => {
    const current = editorRef.current;
    if (!current || files.length === 0) return;
    interactedRef.current = true;
    setImageError(null);
    let at = position;
    for (const file of files) {
      const picture = isPicture(file);
      const problem = picture
        ? fileProblem(file, uploadLimit.bytes)
        : file.size > uploadLimit.bytes
          ? messages.issues.attachments.tooLarge(file.name, uploadLimit.label)
          : null;
      if (problem) {
        setImageError(problem);
        continue;
      }
      setUploading((count) => count + 1);
      try {
        let node: Record<string, unknown>;
        if (picture) {
          node = { type: "image", attrs: { src: await uploadEditorImage(file), alt: file.name } };
        } else {
          const stored = await uploadEditorFile(file);
          node = { type: "fileBlock", attrs: { src: stored.url, name: stored.name, size: stored.size } };
        }
        if (current.isDestroyed) return;
        if (at === undefined) {
          current.chain().focus().insertContent(node).run();
        } else {
          at = Math.min(at, current.state.doc.content.size);
          current.chain().focus().insertContentAt(at, node).run();
          at += 1;
        }
      } catch (error) {
        setImageError(`${picture ? m.imageFailed : m.fileFailed} ${settingsErrorMessage(error)}`);
      } finally {
        setUploading((count) => count - 1);
      }
    }
  };
  const insertFilesRef = useRef(insertFiles);
  useEffect(() => {
    insertFilesRef.current = insertFiles;
  });
  useImperativeHandle(ref, () => ({ insertFiles: (files) => insertFilesRef.current(files) }), []);
  const createDocumentRef = useRef(onCreateDocument);
  useEffect(() => {
    createDocumentRef.current = onCreateDocument;
  }, [onCreateDocument]);
  // "/문서" first asks for the document's title, and "/임베드" for the address, in a small field at the cursor.
  const [naming, setNaming] = useState<{ top: number; left: number; kind: "document" | "embed" } | null>(null);
  const [namingBusy, setNamingBusy] = useState(false);
  const askAtCursor = (kind: "document" | "embed") => {
    const current = editorRef.current;
    if (!current || (kind === "document" && !createDocumentRef.current)) return;
    interactedRef.current = true;
    setImageError(null);
    const coords = current.view.coordsAtPos(current.state.selection.from);
    const origin = fixedOrigin(probeRef.current);
    setNaming({ top: coords.bottom + 6 - origin.y, left: Math.max(8, coords.left) - origin.x, kind });
  };
  /** Back to the text with nothing made: no title was given. */
  const cancelNaming = () => {
    setNaming(null);
    editorRef.current?.commands.focus();
  };
  const insertDocument = async (rawTitle: string) => {
    const current = editorRef.current;
    const create = createDocumentRef.current;
    const title = rawTitle.trim();
    if (!current || !create || !title) {
      cancelNaming();
      return;
    }
    setNamingBusy(true);
    try {
      const document = await create(title);
      if (current.isDestroyed) return;
      current.storage.pageLink.titles.set(document.id, document.title);
      current.chain().focus().insertContent({ type: "pageLink", attrs: document }).run();
    } catch (error) {
      setImageError(settingsErrorMessage(error));
    } finally {
      setNamingBusy(false);
      setNaming(null);
    }
  };
  /** Puts the Figma file or Outline page at this address in place, or says it cannot. */
  const insertEmbed = (raw: string) => {
    const current = editorRef.current;
    setNaming(null);
    if (!current) return;
    if (!raw.trim()) {
      current.commands.focus();
      return;
    }
    const embed = embedFor(raw);
    if (!embed) {
      setImageError(m.embedUnsupported);
      current.commands.focus();
      return;
    }
    current.chain().focus().insertContent({ type: "embed", attrs: embed }).run();
  };
  const askAtCursorRef = useRef(askAtCursor);
  useEffect(() => {
    askAtCursorRef.current = askAtCursor;
  });
  const slashActions: SlashActions = {
    pickImage: () => fileInputRef.current?.click(),
    pickFile: () => anyFileInputRef.current?.click(),
    createDocument: () => askAtCursorRef.current("document"),
    addEmbed: () => askAtCursorRef.current("embed"),
  };

  const editor = useEditor({
    // Rendered on the server first; the editor attaches in the browser.
    immediatelyRender: false,
    editable,
    autofocus: autoFocus ? "end" : false,
    content: initialContent ?? undefined,
    extensions: plain
      ? [
          // A comment on a block holds text and tags only (D-165): none of the blocks, so that no shortcut can make one.
          StarterKit.configure({
            heading: false,
            bulletList: false,
            orderedList: false,
            listItem: false,
            listKeymap: false,
            blockquote: false,
            codeBlock: false,
            horizontalRule: false,
            link: { openOnClick: false, autolink: true, defaultProtocol: "https" },
          }),
          Placeholder.configure({ placeholder: placeholder ?? "" }),
          Mention,
        ]
      : [
          StarterKit.configure({ link: { openOnClick: false, autolink: true, defaultProtocol: "https" } }),
          TaskList,
          TaskItem.configure({ nested: true, a11y: { checkboxLabel: (node) => m.taskItemLabel(node.textContent) } }),
          Placeholder.configure({ placeholder: placeholder ?? "" }),
          Image.configure({ allowBase64: false }),
          ...tableExtensions,
          PageLink,
          Mention,
          Embed,
          FileBlock,
          ...(blockIds ? [BlockId] : []),
        ],
    editorProps: {
      attributes: { "aria-label": ariaLabel, role: "textbox", "aria-multiline": "true" },
      handlePaste: (view, event) => {
        if (plain) return false;
        const files = droppedFiles(event.clipboardData?.files);
        if (files.length > 0) {
          void insertFilesRef.current(files);
          return true;
        }
        // A Figma or Outline link pasted on its own is shown in place rather than as a link.
        const text = event.clipboardData?.getData("text/plain")?.trim() ?? "";
        const embed = /\s/.test(text) ? null : embedFor(text);
        if (!embed || view.state.selection.$from.parent.type.name === "codeBlock") return false;
        editorRef.current?.chain().focus().insertContent({ type: "embed", attrs: embed }).run();
        return true;
      },
      handleDrop: (view, event, _slice, moved) => {
        if (plain) return false;
        // `moved` is a block dragged within the document, which the editor handles itself.
        const files = moved ? [] : droppedFiles(event.dataTransfer?.files);
        if (files.length === 0) return false;
        event.preventDefault();
        const position = view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos;
        void insertFilesRef.current(files, position);
        return true;
      },
      handleKeyDown: (view, event) => {
        const state = slashRef.current;
        if (!state || event.isComposing) return false;
        const items = menuItems(state, Boolean(createDocumentRef.current), mentionablesRef.current ?? []);
        if (event.key === "Escape") {
          // Only the menu closes: a dialog around the editor must not take this Escape as its own.
          event.stopPropagation();
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
              {
                pickImage: () => fileInputRef.current?.click(),
                pickFile: () => anyFileInputRef.current?.click(),
                createDocument: () => askAtCursorRef.current("document"),
                addEmbed: () => askAtCursorRef.current("embed"),
              },
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

  // Page blocks show the documents' current titles and open them inside the app.
  const router = useRouter();
  useEffect(() => {
    // A plain editor (a comment) has no page blocks.
    if (!editor || editor.isDestroyed || plain) return;
    editor.storage.pageLink.configure({
      open: (id) => router.push(`/doc/${id}`),
      untitled: m.untitledDocument,
      documents: documents ?? [],
    });
  }, [editor, documents, router, plain]);

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

  const items = slash ? menuItems(slash, Boolean(onCreateDocument), mentionables ?? []) : [];

  return (
    <>
    <div
      // "rich-text-editable": tables leave room above for their toolbar (globals.css).
      // An overlay (the comment markers of the blocks) stands in a margin of its own at the right.
      className={cn("rich-text relative", editable && "rich-text-editable", overlay && "pr-8", className)}
      ref={boxRef}
      onClick={(event) => {
        // A picture of the text opens enlarged; it is also how a read-only text shows one in full.
        const dom = editorRef.current?.view.dom;
        const target = event.target;
        if (dom && target instanceof HTMLImageElement && dom.contains(target) && target.getAttribute("src")) {
          const pictures = Array.from(dom.querySelectorAll("img")).filter((picture) => picture.getAttribute("src"));
          const images = pictures.map((picture) => ({ src: picture.getAttribute("src") ?? "", alt: picture.alt }));
          setViewer({ images, index: Math.max(0, pictures.indexOf(target)) });
          setLinkCard(null);
          return;
        }
        const anchor = clickedLink(event.target, editorRef.current?.view.dom, pressRef.current, event.clientX, event.clientY);
        pressRef.current = null;
        const href = anchor?.getAttribute("href");
        const box = boxRef.current;
        if (!anchor || !href || !box) {
          setLinkCard(null);
          return;
        }
        // Never follow it by itself (an editor that cannot be changed would otherwise open it at once).
        event.preventDefault();
        if (event.metaKey || event.ctrlKey || linkCard?.anchor === anchor) {
          openLink(href);
          setLinkCard(null);
        } else {
          setLinkCard({ href, anchor, ...cardPlace(anchor, box, event.clientY) });
        }
      }}
      onPointerDownCapture={(event) => {
        interactedRef.current = true;
        pressRef.current = pressOn(event.target, editorRef.current?.view.dom, event.clientX, event.clientY);
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
      <MentionPeopleContext value={mentionPeople}>
        <EditorContent editor={editor} />
      </MentionPeopleContext>
      {/* Marks the origin of fixed positioning for the menu and the table toolbar (see fixedOrigin). */}
      <span
        ref={(node) => {
          probeRef.current = node;
          setProbe(node);
        }}
        aria-hidden="true"
        className="pointer-events-none fixed top-0 left-0 size-0"
      />
      {editor && editable && !plain ? <TableToolbar editor={editor} probe={probe} /> : null}
      {editor && editable && !plain ? <BlockDragHandle editor={editor} container={probe?.parentElement ?? null} /> : null}
      {editor && overlay ? overlay({ editor, container: probe?.parentElement ?? null }) : null}
      {editor && linkCard ? <LinkCard card={linkCard} editor={editor} onClose={closeLinkCard} /> : null}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/gif"
        multiple
        hidden
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => {
          const files = droppedFiles(event.target.files);
          event.target.value = "";
          void insertFiles(files);
        }}
      />
      <input
        ref={anyFileInputRef}
        type="file"
        multiple
        hidden
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => {
          const files = droppedFiles(event.target.files);
          event.target.value = "";
          void insertFiles(files);
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
      {naming ? (
        <input
          autoFocus
          disabled={namingBusy}
          maxLength={naming.kind === "embed" ? MAX_EMBED_URL_LENGTH : 200}
          autoComplete="off"
          placeholder={naming.kind === "embed" ? m.embedUrlPlaceholder : m.documentTitlePlaceholder}
          aria-label={naming.kind === "embed" ? m.embedUrlPlaceholder : m.documentTitlePlaceholder}
          style={{ top: naming.top, left: naming.left }}
          className={cn(
            naming.kind === "embed" ? "w-96 max-w-[calc(100vw-1rem)]" : "w-64",
            "fixed z-50 h-9 rounded-lg bg-popover px-3 text-[0.8125rem] leading-5 text-popover-foreground shadow-md ring-1 ring-foreground/10 outline-none placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring",
          )}
          onKeyDown={(event) => {
            // Stays in this field: the editor around it must not take the keys.
            event.stopPropagation();
            if (event.nativeEvent.isComposing) return;
            if (event.key === "Enter") {
              event.preventDefault();
              if (naming.kind === "embed") insertEmbed(event.currentTarget.value);
              else void insertDocument(event.currentTarget.value);
            } else if (event.key === "Escape") {
              event.preventDefault();
              cancelNaming();
            }
          }}
          // Clicking elsewhere gives up, the same as an empty title.
          onBlur={() => {
            if (!namingBusy) setNaming(null);
          }}
        />
      ) : null}
      {slash && editor && items.length > 0 ? (
        <div
          role="listbox"
          aria-label={slash.kind === "mention" ? m.mentionMenu : m.slashMenu}
          style={{
            top: slash.top,
            left: slash.left,
            maxHeight: slash.maxHeight,
            maxWidth: Math.max(MENU_MIN_WIDTH, slash.room),
            transform: slash.above ? "translateY(-100%)" : undefined,
          }}
          className={cn(
            "fixed z-50 flex flex-col overflow-y-auto overscroll-contain rounded-lg bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10",
            // People are told apart by name and address, so their menu grows to show both in full.
            slash.kind === "mention" ? "w-max min-w-56" : "w-56",
          )}
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
              <span className={item.hint ? "shrink-0" : "truncate"}>{item.label}</span>
              {item.hint ? <span className="truncate text-muted-foreground">{item.hint}</span> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
    {viewer ? (
      <ImageViewer
        images={viewer.images}
        index={viewer.index}
        onIndexChange={(index) => setViewer((current) => (current ? { ...current, index } : current))}
        onClose={() => setViewer(null)}
      />
    ) : null}
    </>
  );
}
