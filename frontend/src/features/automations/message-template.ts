import type { IssueStatusItem } from "@/features/issues/status-defaults";

export const MESSAGE_VARIABLES = ["티켓번호", "티켓제목", "이전상태", "현재상태", "변경자", "프로젝트명"] as const;

/** Pick wording from the actual transition, including custom status names. */
export function suggestedMessage(to?: IssueStatusItem, from?: IssueStatusItem): string {
  const name = to?.name.replace(/\s/g, "");
  const previous = from?.name.replace(/\s/g, "");
  const opening = name === "리뷰준비됨" ? "{티켓번호} {티켓제목}의 리뷰가 요청되었습니다."
    : name === "리뷰중" ? "{티켓번호} {티켓제목}의 리뷰가 시작되었습니다."
    : name === "보류" ? "{티켓번호} {티켓제목}의 작업이 보류되었습니다. 사유를 확인해 주세요."
    : to?.category === "DONE" ? "{티켓번호} {티켓제목}의 작업이 완료되었습니다."
    : to?.category === "CANCELED" ? "{티켓번호} {티켓제목}이 취소되었습니다."
    : name === "진행중" && previous?.startsWith("리뷰") ? "{티켓번호} {티켓제목}의 작업이 다시 시작되었습니다. 리뷰 의견을 확인해 주세요."
    : name === "진행중" ? "{티켓번호} {티켓제목}의 작업이 시작되었습니다."
    : to?.category === "READY" ? "{티켓번호} {티켓제목}의 작업 준비가 되었습니다."
    : "{티켓번호} {티켓제목}의 상태가 {현재상태}(으)로 변경되었습니다.";
  return `${opening}\n{이전상태} → {현재상태} · 변경한 사람: {변경자}`;
}

export function previewMessage(message: string, to?: IssueStatusItem, from?: IssueStatusItem): string {
  const values: Record<string, string> = { 티켓번호: "FB-123", 티켓제목: "결제 기능 개선", 이전상태: from?.name ?? "이전 상태", 현재상태: to?.name ?? "현재 상태", 변경자: "김민수", 프로젝트명: "결제 프로젝트" };
  return message.replace(/\{([^{}]+)\}/g, (token, key: string) => Object.hasOwn(values, key) ? values[key] : token);
}
