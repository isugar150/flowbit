import { apiFetch, fileForm } from "@/lib/api/client";

/** Stores a picture for an editor document and returns the address to put in the image block. */
export async function uploadEditorImage(file: File): Promise<string> {
  const { url } = await apiFetch<{ url: string }>("/api/v1/images", { method: "POST", body: fileForm(file) });
  return url;
}

/** What the server says of a file stored for an editor document (D-182): where it is, and its name and size as kept. */
export type UploadedEditorFile = { url: string; name: string; size: number };

/** Stores any file for an editor document; the document keeps it as a file block. */
export function uploadEditorFile(file: File): Promise<UploadedEditorFile> {
  return apiFetch<UploadedEditorFile>("/api/v1/editor-files", { method: "POST", body: fileForm(file) });
}
