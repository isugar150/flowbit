import { useState } from "react";

/** Whether two values that came from JSON (plain objects, arrays, strings, numbers, booleans, null) say the same, all the way down. */
export function sameJson(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((item, index) => sameJson(item, b[index]));
  }
  const left = a as Record<string, unknown>;
  const right = b as Record<string, unknown>;
  const keys = Object.keys(left);
  return keys.length === Object.keys(right).length && keys.every((key) => key in right && sameJson(left[key], right[key]));
}

/**
 * `next` with every item that says the same as the item of `previous` that has its key (`keyOf`) replaced by that one. The server
 * sends a list again as new objects whenever the page is read again (after a save, when someone else changes something): a
 * list line that is kept from drawing again unless its issue changed (`memo`) would otherwise draw every time. When nothing
 * changed at all, `previous` itself comes back.
 */
export function reuseEqual<T>(previous: readonly T[], next: readonly T[], keyOf: (item: T) => string | number): T[] {
  const before = new Map(previous.map((item) => [keyOf(item), item]));
  const reused = next.map((item) => {
    const old = before.get(keyOf(item));
    return old !== undefined && sameJson(old, item) ? old : item;
  });
  const unchanged = reused.length === previous.length && reused.every((item, index) => item === previous[index]);
  return unchanged ? (previous as T[]) : reused;
}

/**
 * The list a page was given, with the items that did not change kept as the objects they were (`reuseEqual`). A list read again that
 * says the same is the same list: nothing downstream draws again.
 */
export function useStableList<T>(incoming: T[], keyOf: (item: T) => string | number): T[] {
  const [seen, setSeen] = useState(incoming);
  const [stable, setStable] = useState(incoming);
  // A new list was given: work out the stable one now, in this render, not in an effect after it was drawn with the old one.
  if (incoming !== seen) {
    setSeen(incoming);
    setStable(reuseEqual(stable, incoming, keyOf));
  }
  return stable;
}
