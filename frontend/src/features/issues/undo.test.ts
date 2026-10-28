import { describe, expect, it } from "vitest";
import { currentNotice, hasUndo, previousOf, recordUndo, undoLabel, undoLast } from "./undo";

describe("undo", () => {
  it("takes the last change back first, one at a time", async () => {
    const order: string[] = [];
    recordUndo("FB-1 상태 변경", async () => void order.push("first"));
    recordUndo("FB-2 우선순위 변경", async () => void order.push("second"));
    expect(currentNotice()?.text).toBe("FB-2 우선순위 변경");
    expect(currentNotice()?.undoable).toBe(true);

    expect(await undoLast()).toBe(true);
    expect(await undoLast()).toBe(true);

    expect(order).toEqual(["second", "first"]);
    expect(hasUndo()).toBe(false);
    expect(await undoLast()).toBe(false);
    expect(currentNotice()?.undoable).toBe(false);
  });
});

describe("undoable changes", () => {
  it("names what was changed in the notice", () => {
    expect(undoLabel("FB-12", { statusId: "a", status: "DONE" })).toBe("FB-12 상태 변경");
    expect(undoLabel("FB-12", { startDate: null, dueDate: "2026-11-01" })).toBe("FB-12 기한 변경");
    expect(undoLabel("FB-12", { priority: "HIGH", assigneeIds: [] })).toBe("FB-12 우선순위·담당자 변경");
  });

  it("reads the values to put back from what the issue had", () => {
    const before = { priority: "LOW", assigneeIds: ["a"], dueDate: null };
    expect(previousOf(before, { priority: "HIGH", dueDate: "2026-11-01" })).toEqual({ priority: "LOW", dueDate: null });
  });
});
