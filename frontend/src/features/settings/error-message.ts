import { ApiError, NetworkError } from "@/lib/api/errors";
import { messages } from "@/messages";
import { formatLimit } from "./upload-limit";

const m = messages.settings.image;

export const ACCEPTED_IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp", "image/gif"];

/**
 * Why a file cannot be uploaded, or null when it looks fine. `limitBytes` is what the server takes
 * (`useUploadLimit`); it is checked here only to fail fast, the server checks again.
 */
export function fileProblem(file: File, limitBytes: number): string | null {
  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) return m.unsupportedType;
  if (file.size > limitBytes) return m.tooLarge(formatLimit(limitBytes));
  return null;
}

/** The sentence to show when a settings request fails. */
export function settingsErrorMessage(error: unknown): string {
  if (error instanceof NetworkError) return messages.common.networkError;
  if (!(error instanceof ApiError)) return messages.common.serverError;

  switch (error.code) {
    case "FILE_TOO_LARGE":
      // The server's sentence names the limit that is set.
      return error.message || m.tooLargeAny;
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
