import { describe, expect, it } from "vitest";
import { activityText } from "./card-activity";

describe("activityText", () => {
  it("puts the value after the words when there is one", () => {
    expect(activityText("님이 상태를 바꿨습니다:", "진행 중 → 완료", true)).toBe("님이 상태를 바꿨습니다: 진행 중 → 완료");
  });

  it("leaves no colon hanging when there is no value", () => {
    expect(activityText("님이 상태를 바꿨습니다:", undefined, true)).toBe("님이 상태를 바꿨습니다");
    expect(activityText("님이 티켓을 만들었습니다", undefined, true)).toBe("님이 티켓을 만들었습니다");
  });

  it("starts at what was done when nobody is named", () => {
    expect(activityText("님이 우선순위를 바꿨습니다:", undefined, false)).toBe("우선순위를 바꿨습니다");
    expect(activityText("님이 브랜치를 연결했습니다:", "feature/FB-1", false)).toBe("브랜치를 연결했습니다: feature/FB-1");
  });
});
