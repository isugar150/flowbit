import { describe, expect, it } from "vitest";
import { appLinkTarget } from "./app-link";

const origin = "https://issues.motherk.link";

describe("PWA protocol launch", () => {
  it("opens only the ticket path on the current installation", () => {
    expect(appLinkTarget("web+flowbit://issues.motherk.link/issue/MK-9", origin)).toBe("/issue/MK-9");
    expect(appLinkTarget("web+flowbit://ISSUES.MOTHERK.LINK:443/issue/MK-9", origin)).toBe("/issue/MK-9");
    expect(appLinkTarget("web+flowbit://localhost:3100/issue/FB-12", "http://localhost:3100")).toBe("/issue/FB-12");
  });

  it.each([
    null, "", "https://issues.motherk.link/issue/MK-9", "javascript:alert(1)",
    "web+flowbit://other.example.com/issue/MK-9", "web+flowbit://issues.motherk.link:444/issue/MK-9",
    "web+flowbit://user:password@issues.motherk.link/issue/MK-9", "web+flowbit://issues.motherk.link/admin",
    "web+flowbit://issues.motherk.link/open-app", "web+flowbit://issues.motherk.link/issue/MK-0",
    "web+flowbit://issues.motherk.link/issue/MK-9?next=https://evil.example",
    "web+flowbit://issues.motherk.link/issue/MK-9#fragment", "web+flowbit://issues.motherk.link/issue/%2f%2fevil.example",
    "web+flowbit://issues.motherk.link\\@evil.example/issue/MK-9", "web+flowbit://issues.motherk.link/issue/MK-9\n",
  ])("rejects untrusted or unsupported launch URL %s", (raw) => {
    expect(appLinkTarget(raw, origin)).toBeNull();
  });
});
