/** The kinds of file that are shown in the text and in a window over the page (D-187). */
export type PreviewKind = "docx" | "xlsx" | "pptx" | "pdf" | "txt" | "csv" | "tsv";

/** The kind that is plain text: put into the page as the text, not drawn by a viewer library. */
export type TextKind = "txt";

/** The kinds the Office viewer library draws (a CSV or TSV file is read as a sheet of one table). */
export type OfficeKind = "docx" | "xlsx" | "pptx" | "csv" | "tsv";

/** Where a viewer is: reading the file, showing it, or unable to (the file is no good, or could not be fetched). */
export type ViewerStage = "loading" | "ready" | "failed";

const KINDS: Record<string, PreviewKind> = { docx: "docx", xlsx: "xlsx", pptx: "pptx", pdf: "pdf", txt: "txt", csv: "csv", tsv: "tsv" };

/**
 * The largest file that is read into the browser to be previewed. The viewers parse the whole file in memory, so a bigger one
 * is only offered for download. Uploads are capped far lower (5MB unless the administrator raises it).
 */
export const MAX_PREVIEW_BYTES = 30 * 1024 * 1024;

/** The same for text, which is put into the page as it is: less is read, and only the beginning of it is shown (a table is not text in the page: the viewer draws it). */
export const MAX_TEXT_PREVIEW_BYTES = 10 * 1024 * 1024;

/** How much of a text file, in characters, is shown. The rest is in the download. */
export const MAX_TEXT_CHARS = 300_000;

/** How a file is previewed, from the extension of the name it was uploaded with; null when it cannot be (the old .doc/.xls/.ppt too). */
export function previewKindOf(name: string): PreviewKind | null {
  const dot = name.lastIndexOf(".");
  if (dot < 0) return null;
  return KINDS[name.slice(dot + 1).toLowerCase()] ?? null;
}

export const isTextKind = (kind: PreviewKind): kind is TextKind => kind === "txt";

/** Whether a file block shows the file in the text, rather than only offering a download. */
export function isPreviewable(name: string, size: number): boolean {
  const kind = previewKindOf(name);
  if (kind === null) return false;
  return size <= (isTextKind(kind) ? MAX_TEXT_PREVIEW_BYTES : MAX_PREVIEW_BYTES);
}
