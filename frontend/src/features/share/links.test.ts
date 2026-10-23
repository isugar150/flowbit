import { describe, expect, it } from "vitest";
import { documentAddress, embedCode, issueAddress, shareUrl } from "./links";

describe("shareUrl", () => {
  it("puts the path of the link after the address the site is opened at", () => {
    expect(shareUrl("https://flowbit.example.com", "/s/abc_DEF-123")).toBe("https://flowbit.example.com/s/abc_DEF-123");
  });
});

describe("embedCode", () => {
  it("makes a frame of the address that fills the width of the page it is put on", () => {
    expect(embedCode("https://flowbit.example.com/s/abc", "10월 캘린더")).toBe(
      '<iframe src="https://flowbit.example.com/s/abc" title="10월 캘린더" width="100%" height="640" style="border:0" loading="lazy"></iframe>',
    );
  });

  it("writes characters of the title that mean something in HTML as text", () => {
    expect(embedCode("https://x.test/s/a", 'A & "B" <C>')).toContain('title="A &amp; &quot;B&quot; &lt;C&gt;"');
  });
});

describe("addresses inside a shared issue", () => {
  it("makes the shared issue the link itself and every other issue or document a page of it", () => {
    expect(issueAddress("tok_en-1", 12, 12)).toBe("/s/tok_en-1");
    expect(issueAddress("tok_en-1", 12, 15)).toBe("/s/tok_en-1?issue=15");
    expect(documentAddress("tok_en-1", "0198a1b2-0000-7000-8000-000000000001")).toBe("/s/tok_en-1?doc=0198a1b2-0000-7000-8000-000000000001");
  });
});
