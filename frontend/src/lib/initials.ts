const HANGUL = /^[가-힣]+$/;

/**
 * Letters for a person's avatar without a photo. Korean names show the given name (the last two
 * syllables of "김지민" are "지민"); anything else shows its first two characters.
 */
export function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "?";
  const compact = trimmed.replace(/\s+/g, "");
  if (HANGUL.test(compact)) return compact.length <= 2 ? compact : compact.slice(-2);
  return Array.from(compact).slice(0, 2).join("").toUpperCase();
}

/**
 * One letter, for avatars too small to hold two (under 24px): the first of what `initials` gives,
 * so "김지민" is "지" and "admin" is "A".
 */
export function initial(name: string): string {
  return Array.from(initials(name))[0] ?? "?";
}

/**
 * Letters for something that is not a person (the workspace): the start of its name, so
 * "마더케이" is "마더", not the "케이" a person's name would give. `length` 1 for small marks.
 */
export function mark(name: string, length: 1 | 2 = 2): string {
  const compact = name.trim().replace(/\s+/g, "");
  if (!compact) return "?";
  return Array.from(compact).slice(0, length).join("").toUpperCase();
}
