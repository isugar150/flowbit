/** The kinds of file that open in the preview over the page (D-187). */
export type PreviewKind = "docx" | "xlsx" | "pptx";

const KINDS: Record<string, PreviewKind> = { docx: "docx", xlsx: "xlsx", pptx: "pptx" };

/**
 * The largest file that is read into the browser to be previewed. The viewer parses the whole file in memory, so a bigger one
 * is only offered for download. Uploads are capped far lower (5MB unless the administrator raises it).
 */
export const MAX_PREVIEW_BYTES = 30 * 1024 * 1024;

/** How a file is previewed, from the extension of the name it was uploaded with; null when it cannot be (the old .doc/.xls/.ppt too). */
export function previewKindOf(name: string): PreviewKind | null {
  const dot = name.lastIndexOf(".");
  if (dot < 0) return null;
  return KINDS[name.slice(dot + 1).toLowerCase()] ?? null;
}

/** Whether a file block opens a preview when it is clicked, rather than only downloading. */
export function isPreviewable(name: string, size: number): boolean {
  return previewKindOf(name) !== null && size <= MAX_PREVIEW_BYTES;
}
