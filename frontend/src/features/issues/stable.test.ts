import { describe, expect, it } from "vitest";
import { reuseEqual, sameJson } from "./stable";

describe("sameJson", () => {
  it("is true for what says the same all the way down, in whatever order the keys come", () => {
    expect(sameJson({ a: 1, b: [1, { c: "x" }], d: null }, { d: null, b: [1, { c: "x" }], a: 1 })).toBe(true);
    expect(sameJson([], [])).toBe(true);
    expect(sameJson("x", "x")).toBe(true);
  });

  it("is false when any value, key or length differs", () => {
    expect(sameJson({ a: 1 }, { a: 2 })).toBe(false);
    expect(sameJson({ a: 1 }, { a: 1, b: 2 })).toBe(false);
    expect(sameJson({ a: 1, b: undefined }, { a: 1, c: undefined })).toBe(false);
    expect(sameJson([1, 2], [1, 2, 3])).toBe(false);
    expect(sameJson([1], { 0: 1 })).toBe(false);
    expect(sameJson(null, {})).toBe(false);
    expect(sameJson({ a: [{ b: 1 }] }, { a: [{ b: 2 }] })).toBe(false);
  });
});

describe("reuseEqual", () => {
  const key = (item: { id: number }) => item.id;

  it("keeps the old object of every item that says the same, and takes the new one of the others", () => {
    const one = { id: 1, title: "첫 티켓", labels: [{ name: "버그" }] };
    const two = { id: 2, title: "둘째 티켓", labels: [] };
    const before = [one, two];
    // Read again: all new objects, the second one changed.
    const after = [
      { id: 1, title: "첫 티켓", labels: [{ name: "버그" }] },
      { id: 2, title: "둘째 티켓 (수정)", labels: [] },
    ];

    const reused = reuseEqual(before, after, key);

    expect(reused[0]).toBe(one);
    expect(reused[1]).toBe(after[1]);
    expect(reused).not.toBe(before);
  });

  it("gives back the old list itself when nothing changed", () => {
    const before = [{ id: 1, title: "a" }, { id: 2, title: "b" }];
    const reused = reuseEqual(before, [{ id: 1, title: "a" }, { id: 2, title: "b" }], key);
    expect(reused).toBe(before);
  });

  it("follows the new list: new items, gone items and the new order", () => {
    const one = { id: 1, title: "a" };
    const two = { id: 2, title: "b" };
    const three = { id: 3, title: "c" };
    const reused = reuseEqual([one, two], [{ id: 3, title: "c" }, { id: 2, title: "b" }], key);
    expect(reused.map((item) => item.id)).toEqual([3, 2]);
    expect(reused[0]).not.toBe(three);
    expect(reused[1]).toBe(two);
    expect(reused).not.toContain(one);
  });
});
