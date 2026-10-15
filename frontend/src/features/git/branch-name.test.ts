import { describe, expect, it } from "vitest";
import { branchName } from "./branch-name";

describe("branchName", () => {
  it("joins the login id, the key and a slug of the title", () => {
    expect(branchName("kimjm", "FB-12", "Login error on Safari")).toBe("kimjm/fb-12-login-error-on-safari");
  });

  it("leaves the slug out of a Korean title", () => {
    expect(branchName("kimjm", "FB-12", "로그인 오류")).toBe("kimjm/fb-12");
    expect(branchName("kimjm", "FB-12", "로그인 오류 (Safari 17)")).toBe("kimjm/fb-12-safari-17");
  });

  it("takes the part of an e-mail login id before @", () => {
    expect(branchName("Kim.JM@example.com", "FB-3", "Fix")).toBe("kim.jm/fb-3-fix");
  });

  it("stops at 60 characters without a trailing dash", () => {
    const name = branchName("kimjm", "FB-12", "a very long title that keeps going well past the limit of sixty characters");
    expect(name.length).toBeLessThanOrEqual(60);
    expect(name.endsWith("-")).toBe(false);
    expect(name.startsWith("kimjm/fb-12-a-very-long-title")).toBe(true);
  });
});
