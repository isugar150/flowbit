import { ApiError, NetworkError } from "@/lib/api/errors";
import { messages } from "@/messages";

/**
 * The sentence shown above a form when a request fails for a reason that is not tied to one field.
 * Returns null for validation failures, which are shown under the fields instead.
 */
export function formErrorMessage(error: unknown): string | null {
  if (error instanceof NetworkError) return messages.common.networkError;
  if (!(error instanceof ApiError)) return messages.common.serverError;

  if (error.code === "VALIDATION_FAILED" && error.fieldErrors.length > 0) return null;
  if (error.code === "INVALID_CREDENTIALS") return messages.login.invalidCredentials;
  if (error.code === "SETUP_ALREADY_COMPLETED") return messages.setup.alreadyCompleted;
  if (error.status === 429) return messages.common.tooManyRequests(error.retryAfterSeconds ?? 60);
  if (error.status >= 500) return messages.common.serverError;
  return error.message || messages.common.serverError;
}
