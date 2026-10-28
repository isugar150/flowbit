import { describe, expect, it } from "vitest";
import { GO_TARGETS, SHORTCUTS, SHORTCUT_GROUPS, shortcutOf, shortcutRows } from "./shortcuts";

describe("shortcuts", () => {
  it("has each id once, and every group is a known one", () => {
    const ids = SHORTCUTS.map((shortcut) => shortcut.id);
    expect(new Set(ids).size).toBe(ids.length);
    const groups = new Set(SHORTCUT_GROUPS.map((group) => group.id));
    expect(SHORTCUTS.every((shortcut) => groups.has(shortcut.group))).toBe(true);
  });

  it("does not give one combo to two shortcuts of the same group", () => {
    for (const group of SHORTCUT_GROUPS) {
      const combos = SHORTCUTS.filter((shortcut) => shortcut.group === group.id).flatMap((shortcut) => shortcut.keys);
      expect(new Set(combos).size).toBe(combos.length);
    }
  });

  it("names a shortcut for every place G goes to, with the matching letter", () => {
    for (const target of GO_TARGETS) {
      const shortcut = shortcutOf(target.shortcut);
      expect(shortcut.keys[0]).toBe(`G ${target.code.replace("Key", "")}`);
    }
  });

  it("gives a group's rows to the help table", () => {
    const rows = shortcutRows("everywhere");
    expect(rows.some((row) => row.keys.includes("Mod+K"))).toBe(true);
    expect(rows.every((row) => row.action.length > 0)).toBe(true);
  });
});
