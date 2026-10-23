import { useEffect, useState } from "react";

/** How many lines of a long list are drawn at first, and how many are added at a time after that. */
export const FIRST_LINES = 120;
export const NEXT_LINES = 240;
/** How long to wait between two of those steps, so that the page can be used and painted in between. */
const STEP_MS = 30;

/**
 * How many lines of a list of `total` are to be drawn now (D-206). A thousand lines drawn at once keep the page from answering
 * for a moment, so the first [FIRST_LINES] are drawn (and sent as HTML) and the rest follow in steps of [NEXT_LINES] while
 * the page stays usable. Nothing after the first step changes for what is already drawn. Not `enabled` (another view of the same
 * issues is shown) it stops where it is.
 */
export function useProgressiveCount(total: number, enabled = true): number {
  const [count, setCount] = useState(FIRST_LINES);
  useEffect(() => {
    if (!enabled || count >= total) return;
    const timer = setTimeout(() => setCount((current) => current + NEXT_LINES), STEP_MS);
    return () => clearTimeout(timer);
  }, [count, total, enabled]);
  return count;
}

/**
 * The groups of a list with only their first `budget` lines altogether, in order: the groups keep their place and their count
 * of lines (a group the budget does not reach has its heading and no lines yet).
 */
export function withinBudget<G extends { rows: readonly unknown[] }>(groups: G[], budget: number): (G & { drawn: G["rows"] })[] {
  let left = Math.max(0, budget);
  return groups.map((group) => {
    const drawn = group.rows.slice(0, left);
    left -= drawn.length;
    return { ...group, drawn };
  });
}
