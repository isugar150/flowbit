const HANGUL = /^[가-힣]+$/;

/**
 * Letters for an avatar without a photo. Korean names show the given name (the last two
 * syllables of "김지민" are "지민"); anything else shows its first two characters.
 */
export function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "?";
  const compact = trimmed.replace(/\s+/g, "");
  if (HANGUL.test(compact)) return compact.length <= 2 ? compact : compact.slice(-2);
  return Array.from(compact).slice(0, 2).join("").toUpperCase();
}
