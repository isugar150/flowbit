import { ApiError, NetworkError } from "@/lib/api/errors";
import { messages } from "@/messages";

/**
 * The sentence to show when a request fails. The backend already writes problem titles and field
 * messages in Korean for people to read, so they are shown as they are.
 */
export function apiErrorMessage(error: unknown): string {
  if (error instanceof NetworkError) return messages.common.networkError;
  if (!(error instanceof ApiError)) return messages.common.serverError;
  if (error.status === 429) return messages.common.tooManyRequests(error.retryAfterSeconds ?? 60);
  if (error.status >= 500) return messages.common.serverError;
  return error.fieldErrors[0]?.message ?? (error.message || messages.common.serverError);
}

/** Field messages from a validation failure, keyed by field name. */
export function fieldErrorsOf(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {};
  return Object.fromEntries(error.fieldErrors.map((fieldError) => [fieldError.field, fieldError.message]));
}
