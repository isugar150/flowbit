// Made once: every date on the page is read again each time the shared clock ticks (components/dates.tsx).
const RELATIVE_TIME = new Intl.RelativeTimeFormat("ko", { numeric: "auto" });
const STEPS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 31_536_000],
  ["month", 2_592_000],
  ["week", 604_800],
  ["day", 86_400],
  ["hour", 3_600],
  ["minute", 60],
];

/**
 * "3분 전", "어제", "2주 전": how long ago, in the reader's words.
 * Reads the clock, so render it through `<TimeAgo>` (components/dates.tsx), never directly.
 */
export function timeAgo(iso: string, now = Date.now()): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  for (const [unit, size] of STEPS) {
    if (Math.abs(seconds) >= size) return RELATIVE_TIME.format(Math.round(seconds / size), unit);
  }
  return RELATIVE_TIME.format(0, "second");
}
