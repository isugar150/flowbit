import { describe, expect, it, vi } from "vitest";
import { headers } from "next/headers";
import manifest from "./manifest";

vi.mock("next/headers", () => ({ headers: vi.fn() }));

describe("PWA installation relationship", () => {
  it("references this installation's absolute app identity without changing the installed app ID", async () => {
    vi.mocked(headers).mockResolvedValue(new Headers({ host: "issues.motherk.link", "x-forwarded-proto": "https" }) as Awaited<ReturnType<typeof headers>>);
    const result = await manifest();
    expect(result.id).toBe("/");
    expect(result.related_applications).toEqual([{ platform: "webapp", id: "https://issues.motherk.link/", url: "https://issues.motherk.link/manifest.webmanifest" }]);
    expect(result.prefer_related_applications).toBe(false);
  });
  it("keeps localhost ports in the app identity", async () => {
    vi.mocked(headers).mockResolvedValue(new Headers({ host: "localhost:3100", "x-forwarded-proto": "http" }) as Awaited<ReturnType<typeof headers>>);
    expect((await manifest()).related_applications?.[0].id).toBe("http://localhost:3100/");
  });
});
