import { describe, expect, it } from "vitest";
import { draftsToInput, draftWithBadNumber, newDraft, withoutPeople, type DraftProperty } from "./drafts";

const withValue = (draft: DraftProperty, value: DraftProperty["value"]): DraftProperty => ({ ...draft, value });

describe("drafts", () => {
  it("starts each kind without a value, a checkbox unchecked", () => {
    expect(newDraft("메모", "TEXT").value).toBe("");
    expect(newDraft("예산", "NUMBER").value).toBe("");
    expect(newDraft("명세", "LINK").value).toBe("");
    expect(newDraft("배포예정일", "DATE").value).toBeNull();
    expect(newDraft("QA 담당", "PERSON").value).toBeNull();
    expect(newDraft("검수", "CHECKBOX").value).toBe(false);
  });

  it("gives each one a key of its own", () => {
    expect(newDraft("a", "TEXT").key).not.toBe(newDraft("a", "TEXT").key);
  });

  it("turns what was typed into the values the server takes, in order", () => {
    const drafts = [
      withValue(newDraft("담당 부서", "TEXT"), "  결제팀 "),
      withValue(newDraft("예산", "NUMBER"), "1,500.5"),
      withValue(newDraft("배포예정일", "DATE"), "2026-11-01"),
      withValue(newDraft("검수", "CHECKBOX"), true),
      withValue(newDraft("명세", "LINK"), "https://example.com/spec"),
      withValue(newDraft("QA 담당", "PERSON"), "user-1"),
      newDraft("비고", "TEXT"),
      newDraft("빈 숫자", "NUMBER"),
    ];
    expect(draftsToInput(drafts)).toEqual([
      { label: "담당 부서", type: "TEXT", value: "결제팀" },
      { label: "예산", type: "NUMBER", value: 1500.5 },
      { label: "배포예정일", type: "DATE", value: "2026-11-01" },
      { label: "검수", type: "CHECKBOX", value: true },
      { label: "명세", type: "LINK", value: "https://example.com/spec" },
      { label: "QA 담당", type: "PERSON", value: "user-1" },
      { label: "비고", type: "TEXT", value: null },
      { label: "빈 숫자", type: "NUMBER", value: null },
    ]);
  });

  it("finds no bad number when the numbers are numbers or blank", () => {
    expect(draftWithBadNumber([withValue(newDraft("예산", "NUMBER"), "1,000"), newDraft("빈 숫자", "NUMBER")])).toBeUndefined();
  });

  it("empties the people when the project changes and leaves the rest", () => {
    const person = withValue(newDraft("QA 담당", "PERSON"), "user-1");
    const text = withValue(newDraft("메모", "TEXT"), "a");
    expect(withoutPeople([person, text])).toEqual([{ ...person, value: null }, text]);
  });
});
