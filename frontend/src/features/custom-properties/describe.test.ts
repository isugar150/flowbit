import { describe, expect, it } from "vitest";
import { messages } from "../../messages";
import { describeCustomProperty } from "./describe";

const words = messages.issues.activity;

describe("사람 속성의 활동", () => {
  it("리뷰어 이름과 추가한 사람 및 제거한 사람을 표시한다", () => {
    expect(describeCustomProperty({ op: "CHANGED", label: "리뷰어", propertyType: "PERSON", added: ["김철수"], removed: ["이영희"] }, words)).toEqual({
      action: "님이 '리뷰어' 속성을 바꿨습니다:", change: "추가: 김철수 · 제거: 이영희",
    });
  });

  it("추가만 하거나 모두 제거해도 해당 변경만 표시한다", () => {
    expect(describeCustomProperty({ label: "검토자", propertyType: "PERSON", added: ["김철수", "이영희"], removed: [] }, words).change).toBe("추가: 김철수, 이영희");
    expect(describeCustomProperty({ label: "검토자", propertyType: "PERSON", added: [], removed: ["김철수"] }, words).change).toBe("제거: 김철수");
  });

  it("이전 기록과 다른 형식의 속성은 변경 전후 값을 유지한다", () => {
    expect(describeCustomProperty({ label: "리뷰어", from: "김철수", to: null }, words)).toEqual({
      action: "님이 '리뷰어' 속성을 바꿨습니다:", change: "김철수 → 없음",
    });
    expect(describeCustomProperty({ label: "메모", from: "초안", to: "확정" }, words).change).toBe("초안 → 확정");
  });
});
