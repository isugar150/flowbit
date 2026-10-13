import { expect, type BrowserContext, type Page, type Route } from "@playwright/test";

/** The account these tests make on an empty database. It exists nowhere else. */
export const ADMIN = { loginId: "e2e-admin", password: "e2e-password-1234" };
export const WORKSPACE = { workspaceName: "E2E", workspaceKey: "E2E" };

/** Calls the backend the way the app does: same origin, with the CSRF header on changes. */
export async function api<T>(context: BrowserContext, method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET") {
    const token = async () => (await context.cookies()).find((cookie) => cookie.name === "XSRF-TOKEN")?.value;
    if (!(await token())) await context.request.get("/api/v1/auth/csrf");
    headers["X-XSRF-TOKEN"] = decodeURIComponent((await token()) ?? "");
  }
  const response = await context.request.fetch(path, { method, headers, data: body });
  if (!response.ok()) throw new Error(`${method} ${path} → ${response.status()} ${await response.text()}`);
  return (response.status() === 204 ? undefined : await response.json()) as T;
}

/** Signs in, making the workspace first when the database is new. */
export async function signIn(context: BrowserContext): Promise<{ id: string }> {
  try {
    await api(context, "POST", "/api/v1/setup", { ...WORKSPACE, ...ADMIN });
  } catch {
    // Already set up by an earlier run.
  }
  return api<{ id: string }>(context, "POST", "/api/v1/auth/login", ADMIN);
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Slows the two things a change waits for, so that every state in between is on screen long
 * enough to be seen: the change itself, and the re-render of the page that follows it (the
 * requests Next.js marks with the `RSC` header).
 */
export async function slowDown(page: Page, { changeMs = 300, refreshMs = 500 } = {}) {
  trackRequests(page);
  await page.route("**/*", async (route: Route) => {
    const request = route.request();
    if (request.method() !== "GET" && request.url().includes("/api/v1/")) await wait(changeMs);
    else if (request.headers()["rsc"] === "1") await wait(refreshMs);
    await route.fallback();
  });
}

declare global {
  interface Window {
    __frames?: Record<string, string[]>;
  }
}

/**
 * Records what `read` returns on every painted frame, keeping each value once per run of equal
 * frames. `read` runs in the page. What comes back from `frames` is everything a person could
 * have seen, in order: a value that shows for one frame and goes away is in it.
 */
export async function recordFrames<A = undefined>(page: Page, name: string, read: (arg: A) => string, arg?: A) {
  await page.evaluate(
    ({ name, source, arg }) => {
      const fn = new Function("arg", `return (${source})(arg)`) as (arg: unknown) => string;
      const seen: string[] = [];
      window.__frames = { ...window.__frames, [name]: seen };
      const tick = () => {
        if (window.__frames?.[name] !== seen) return;
        let value: string;
        try {
          value = fn(arg);
        } catch (error) {
          value = `error: ${String(error)}`;
        }
        if (seen[seen.length - 1] !== value) seen.push(value);
        requestAnimationFrame(tick);
      };
      tick();
    },
    { name, source: read.toString(), arg },
  );
}

export async function frames(page: Page, name: string): Promise<string[]> {
  return page.evaluate((key) => window.__frames?.[key] ?? [], name);
}

const inFlight = new WeakMap<Page, { count: number; quietSince: number }>();

/** Counts the page's open requests from here on, for `settled`. Call it before the first change. */
export function trackRequests(page: Page) {
  if (inFlight.has(page)) return;
  const state = { count: 0, quietSince: Date.now() };
  inFlight.set(page, state);
  page.on("request", () => {
    state.count += 1;
  });
  const done = () => {
    state.count = Math.max(0, state.count - 1);
    state.quietSince = Date.now();
  };
  page.on("requestfinished", done);
  page.on("requestfailed", done);
}

/**
 * Waits until nothing the page asked for has been on its way for a while: the change, the
 * re-render after it and the links fetched again behind that have all landed and been painted.
 */
export async function settled(page: Page, quietMs = 700) {
  const state = inFlight.get(page);
  if (!state) throw new Error("trackRequests(page) was not called");
  await page.waitForTimeout(100);
  await expect.poll(() => state.count === 0 && Date.now() - state.quietSince >= quietMs, { timeout: 30_000 }).toBe(true);
}

/** Picks an item of the menu a chip opens. */
export async function pick(page: Page, chip: ReturnType<Page["locator"]>, item: string) {
  await chip.click();
  // By its text: an item's name may start with what its icon says (a person's initial).
  await page.getByRole("menuitemradio").filter({ hasText: item }).click();
}

/** Fails the test when the document is loaded again: an in-app change must never do that. */
export function watchForReloads(page: Page): () => void {
  let reloads = 0;
  page.on("request", (request) => {
    if (request.isNavigationRequest() && request.frame() === page.mainFrame()) reloads += 1;
  });
  return () => expect(reloads, "the document was loaded again").toBe(0);
}
