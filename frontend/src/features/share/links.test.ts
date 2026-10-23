import { describe, expect, it } from "vitest";
import { describeClient, documentAddress, embedCode, issueAddress, shareUrl } from "./links";

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

describe("describeClient", () => {
  it("tells the common browsers and systems apart", () => {
    const chromeOnWindows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    const edgeOnWindows = `${chromeOnWindows} Edg/124.0.0.0`;
    const firefoxOnLinux = "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0";
    const safariOnMac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15";
    const safariOnIphone =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1";
    const chromeOnAndroid =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    expect(describeClient(chromeOnWindows)).toEqual({ browser: "Chrome", os: "Windows" });
    expect(describeClient(edgeOnWindows)).toEqual({ browser: "Edge", os: "Windows" });
    expect(describeClient(firefoxOnLinux)).toEqual({ browser: "Firefox", os: "Linux" });
    expect(describeClient(safariOnMac)).toEqual({ browser: "Safari", os: "macOS" });
    expect(describeClient(safariOnIphone)).toEqual({ browser: "Safari", os: "iOS" });
    expect(describeClient(chromeOnAndroid)).toEqual({ browser: "Chrome", os: "Android" });
  });

  it("knows nothing of a missing or unfamiliar User-Agent", () => {
    expect(describeClient(null)).toEqual({ browser: null, os: null });
    expect(describeClient("curl/8.5.0")).toEqual({ browser: null, os: null });
  });
});

describe("addresses inside a shared issue", () => {
  it("makes the shared issue the link itself and every other issue or document a page of it", () => {
    expect(issueAddress("tok_en-1", 12, 12)).toBe("/s/tok_en-1");
    expect(issueAddress("tok_en-1", 12, 15)).toBe("/s/tok_en-1?issue=15");
    expect(documentAddress("tok_en-1", "0198a1b2-0000-7000-8000-000000000001")).toBe("/s/tok_en-1?doc=0198a1b2-0000-7000-8000-000000000001");
  });
});
