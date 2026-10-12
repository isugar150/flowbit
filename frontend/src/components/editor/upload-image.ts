import { apiFetch } from "@/lib/api/client";

/** Stores a picture for an editor document and returns the address to put in the image block. */
export async function uploadEditorImage(file: File): Promise<string> {
  const form = new FormData();
  form.append("file", file);
  const { url } = await apiFetch<{ url: string }>("/api/v1/images", { method: "POST", body: form });
  return url;
}
