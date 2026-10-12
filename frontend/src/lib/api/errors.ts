export type FieldError = {
  field: string;
  code: string;
  message: string;
};

/** A problem document from the backend (docs/06-api-conventions.md, "오류 형식"). */
export class ApiError extends Error {
  readonly status: number;
  /** Stable identifier to branch on. The message may change; this does not. */
  readonly code: string;
  readonly fieldErrors: FieldError[];
  readonly retryAfterSeconds: number | null;

  constructor(init: {
    status: number;
    code: string;
    message: string;
    fieldErrors?: FieldError[];
    retryAfterSeconds?: number | null;
  }) {
    super(init.message);
    this.name = "ApiError";
    this.status = init.status;
    this.code = init.code;
    this.fieldErrors = init.fieldErrors ?? [];
    this.retryAfterSeconds = init.retryAfterSeconds ?? null;
  }
}

/** The request never reached the server or no response came back. */
export class NetworkError extends Error {
  constructor() {
    super("Network request failed");
    this.name = "NetworkError";
  }
}

function isFieldError(value: unknown): value is FieldError {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.field === "string" && typeof candidate.code === "string" && typeof candidate.message === "string"
  );
}

/** Builds an ApiError from a failed response. Tolerates bodies that are not problem documents. */
export async function toApiError(response: Response): Promise<ApiError> {
  let body: Record<string, unknown> = {};
  try {
    const parsed: unknown = await response.json();
    if (typeof parsed === "object" && parsed !== null) body = parsed as Record<string, unknown>;
  } catch {
    // Not JSON (for example an HTML error page from a proxy in front of the app).
  }
  const retryAfter = Number(response.headers.get("Retry-After"));
  return new ApiError({
    status: response.status,
    code: typeof body.code === "string" ? body.code : "UNKNOWN",
    message: typeof body.title === "string" ? body.title : "",
    fieldErrors: Array.isArray(body.errors) ? body.errors.filter(isFieldError) : [],
    retryAfterSeconds: Number.isFinite(retryAfter) && retryAfter > 0 ? Math.ceil(retryAfter) : null,
  });
}
