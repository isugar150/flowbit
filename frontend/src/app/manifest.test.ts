import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { headers } from "next/headers";
import manifest from "./manifest";

vi.mock("next/headers", () => ({ headers: vi.fn() }));

describe("PWA installation relationship", () => {
  beforeEach(() => vi.stubEnv("FLOWBIT_PUBLIC_URL", ""));
  afterEach(() => vi.unstubAllEnvs());
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
  it("uses the external HTTPS origin when the proxy's final hop reports HTTP", async () => {
    vi.stubEnv("FLOWBIT_PUBLIC_URL", "https://issues.motherk.link/");
    vi.mocked(headers).mockResolvedValue(new Headers({ host: "issues.motherk.link", "x-forwarded-proto": "http" }) as Awaited<ReturnType<typeof headers>>);
    const result = await manifest();
    expect(result.related_applications).toEqual([{ platform: "webapp", id: "https://issues.motherk.link/", url: "https://issues.motherk.link/manifest.webmanifest" }]);
    expect(result.id).toBe("/");
  });
  it.each(["not a URL", "javascript:alert(1)", "https://user:pass@other.example/"])("ignores an unusable public URL %s", async (value) => {
    vi.stubEnv("FLOWBIT_PUBLIC_URL", value);
    vi.mocked(headers).mockResolvedValue(new Headers({ host: "localhost:3100", "x-forwarded-proto": "http" }) as Awaited<ReturnType<typeof headers>>);
    expect((await manifest()).related_applications?.[0].id).toBe("http://localhost:3100/");
  });
});
