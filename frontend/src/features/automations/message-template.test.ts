import { expect, it } from "vitest";
import { BUILT_IN_STATUSES } from "../issues/status-defaults";
import { previewMessage, suggestedMessage } from "./message-template";

it("리뷰 조건에 맞춰 실제 값 대신 변수가 있는 메시지를 제안한다", () => {
  const template = suggestedMessage(BUILT_IN_STATUSES[3], BUILT_IN_STATUSES[2]);
  expect(template).toBe("{티켓번호} {티켓제목}의 리뷰가 요청되었습니다.\n{이전상태} → {현재상태} · 변경한 사람: {변경자}");
  expect(previewMessage(template, BUILT_IN_STATUSES[3], BUILT_IN_STATUSES[2])).toContain("진행 중 → 리뷰 준비됨");
});

it("완료·취소·리뷰 중·사용자 상태에 맞춰 문구를 정한다", () => {
  expect(suggestedMessage(BUILT_IN_STATUSES[4])).toContain("리뷰가 시작");
  expect(suggestedMessage(BUILT_IN_STATUSES[5])).toContain("작업이 완료");
  expect(suggestedMessage(BUILT_IN_STATUSES[6])).toContain("취소");
  expect(suggestedMessage({ ...BUILT_IN_STATUSES[0], name: "보류" })).toContain("보류");
  expect(suggestedMessage({ ...BUILT_IN_STATUSES[2], name: "QA 대기" })).toContain("{현재상태}");
  expect(previewMessage("{알수없음}")).toBe("{알수없음}");
});
