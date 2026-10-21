/** What the server takes until a screen has asked it otherwise: the default of FLOWBIT_MAX_UPLOAD_SIZE. */
export const DEFAULT_UPLOAD_LIMIT = 5 * 1024 * 1024;

/** The limit as a person reads it: "10MB", "1.5MB" or "512KB". */
export function formatLimit(bytes: number): string {
  const megabytes = bytes / (1024 * 1024);
  if (megabytes < 1) return `${Math.round(bytes / 1024)}KB`;
  return `${Number.isInteger(megabytes) ? megabytes : megabytes.toFixed(1)}MB`;
}
