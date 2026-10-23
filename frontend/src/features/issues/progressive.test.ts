import { describe, expect, it } from "vitest";
import { withinBudget } from "./progressive";

const group = (key: string, count: number) => ({ key, rows: Array.from({ length: count }, (_, index) => `${key}${index}`) });

describe("withinBudget", () => {
  it("draws every line of every group when the budget is enough", () => {
    const groups = withinBudget([group("a", 2), group("b", 1)], 10);
    expect(groups.map((entry) => entry.drawn)).toEqual([["a0", "a1"], ["b0"]]);
  });

  it("draws the first lines altogether and leaves the rest of the groups for later, keeping the groups and their lines", () => {
    const groups = withinBudget([group("a", 3), group("b", 3), group("c", 2)], 4);
    expect(groups.map((entry) => entry.drawn)).toEqual([["a0", "a1", "a2"], ["b0"], []]);
    // Every group is still there with all its lines to count.
    expect(groups.map((entry) => entry.rows.length)).toEqual([3, 3, 2]);
    expect(groups.map((entry) => entry.key)).toEqual(["a", "b", "c"]);
  });

  it("draws nothing for a budget of none", () => {
    expect(withinBudget([group("a", 2)], 0)[0].drawn).toEqual([]);
  });
});
