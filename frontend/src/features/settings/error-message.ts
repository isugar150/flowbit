import { ApiError, NetworkError } from "@/lib/api/errors";
import { messages } from "@/messages";

const m = messages.settings.image;

/** Largest file the server accepts by default (FLOWBIT_MAX_UPLOAD_SIZE). Checked here only to fail fast. */
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
export const ACCEPTED_IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp", "image/gif"];

/** Why a file cannot be uploaded, or null when it looks fine. The server checks again. */
export function fileProblem(file: File): string | null {
  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) return m.unsupportedType;
  if (file.size > MAX_UPLOAD_BYTES) return m.tooLarge;
  return null;
}

/** The sentence to show when a settings request fails. */
export function settingsErrorMessage(error: unknown): string {
  if (error instanceof NetworkError) return messages.common.networkError;
  if (!(error instanceof ApiError)) return messages.common.serverError;

  switch (error.code) {
    case "FILE_TOO_LARGE":
      return m.tooLarge;
    case "UNSUPPORTED_IMAGE":
      return m.unreadable;
    case "IMAGE_DIMENSIONS_TOO_LARGE":
      return m.dimensionsTooLarge;
    case "FORBIDDEN":
      return m.forbidden;
  }
  if (error.fieldErrors.length > 0) return error.fieldErrors[0].message;
  if (error.status >= 500) return messages.common.serverError;
  return error.message || messages.common.serverError;
}
