import { describe, expect, it } from "vitest";
import { commentDestination, containsComment } from "./comment-destination";

const id = "019a1234-5678-7000-8000-123456789abc";

describe("댓글 알림 목적지", () => {
  it("일반 댓글과 답글의 UUID를 읽고 다른 앵커는 유지한다", () => {
    expect(commentDestination(`/issue/FB-1#comment-${id}`)).toEqual({ path: "/issue/FB-1", id });
    expect(commentDestination(`/doc/123#comment-${id.toUpperCase()}`)?.id).toBe(id);
    for (const path of ["/issue/FB-1", "/project/1#access-requests", "/issue/FB-1#comment-bad", "/issue/FB-1#comment-<script>"]) {
      expect(commentDestination(path)).toBeNull();
    }
  });
  it("이전 활동 속 부모 댓글·답글·블록 대화를 정확히 찾는다", () => {
    expect(containsComment({ kind: "comment", comment: { id } }, id)).toBe(true);
    expect(containsComment({ kind: "comment", comment: { id: "parent" }, replies: [{ id }] }, id)).toBe(true);
    expect(containsComment({ kind: "thread", thread: { comments: [{ id }] } }, id)).toBe(true);
    expect(containsComment({ kind: "event" }, id)).toBe(false);
    expect(containsComment({ kind: "comment", comment: { id } }, null)).toBe(false);
  });
});
