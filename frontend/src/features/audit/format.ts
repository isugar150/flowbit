/** A moment as `2026-10-28 14:03:21` in the person's time zone (the Swedish format is the ISO-like one). */
export function formatMoment(iso: string, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat("sv-SE", { timeZone, dateStyle: "short", timeStyle: "medium" }).format(new Date(iso));
  } catch {
    return iso;
  }
}
