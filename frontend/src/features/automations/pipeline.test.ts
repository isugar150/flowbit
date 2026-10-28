import { describe, expect, it } from "vitest";
import { moveBlock } from "./pipeline";

describe("자동화 파이프라인", () => {
  const items = [{ id: "notify" }, { id: "assign" }, { id: "label" }];
  it("알림 앞에 담당자 변경을 놓는다", () => {
    expect(moveBlock(items, "assign", 0).map((item) => item.id)).toEqual(["assign", "notify", "label"]);
    expect(items.map((item) => item.id)).toEqual(["notify", "assign", "label"]);
  });
  it("첫 실행을 마지막 틈으로 옮긴다", () => {
    expect(moveBlock(items, "notify", 3).map((item) => item.id)).toEqual(["assign", "label", "notify"]);
  });
});
