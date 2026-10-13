import { expect, test, type Browser, type BrowserContext } from "@playwright/test";
import { api, signIn } from "./support";

/**
 * The onboarding tour (docs/features/onboarding-tour.md): it opens by itself for someone who has
 * not seen it, its example issues can be worked on without anything being saved, finishing or
 * skipping it is remembered, and the settings show it again.
 */

type Me = { id: string; tourCompletedAt: string | null };

const run = Date.now().toString(36);
const password = "e2e-password-5678";

let admin: BrowserContext;

test.beforeAll(async ({ browser, baseURL }) => {
  admin = await browser.newContext({ baseURL });
  await signIn(admin);
});

test.afterAll(async () => {
  await admin.close();
});

/** A person who has never signed in before, so nothing has marked the tour as seen. */
async function newcomer(browser: Browser, baseURL: string | undefined, name: string) {
  const loginId = `e2e-${name}-${run}`;
  await api(admin, "POST", "/api/v1/admin/members", { loginId, password, displayName: `${name} ${run}`, role: "MEMBER" });
  const context = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await api(context, "POST", "/api/v1/auth/login", { loginId, password });
  return context;
}

test("the tour opens on the first visit, the example issues can be worked on, and finishing is remembered", async ({ browser, baseURL }) => {
  const context = await newcomer(browser, baseURL, "tour");
  const issuesBefore = await api<unknown[]>(context, "GET", "/api/v1/me/issues?scope=created");
  // The tour is not in the HTML the server sends: there it would cover the app with buttons that
  // do nothing until (or unless) the page's scripts run.
  expect(await (await context.request.get("/")).text()).not.toContain('role="dialog"');
  const page = await context.newPage();
  await page.goto("/");

  const tour = page.getByRole("dialog");
  await expect(tour).toContainText("Flowbit에 오신 것을 환영합니다");
  await tour.getByRole("button", { name: "둘러보기" }).click();
  await expect(tour).toContainText("메뉴");
  await tour.getByRole("button", { name: "다음" }).click();
  await expect(tour).toContainText("내 프로젝트");
  await tour.getByRole("button", { name: "이전" }).click();
  await expect(tour).toContainText("내 이슈에는");
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("ArrowRight");

  // Create.
  await expect(tour.getByRole("heading")).toHaveText("이슈 만들기");
  const rows = tour.getByRole("listitem");
  await expect(rows).toHaveCount(3);
  await tour.getByRole("textbox").fill("내가 만든 예시");
  await page.keyboard.press("Enter");
  await expect(rows).toHaveCount(4);
  await expect(rows.last()).toContainText("EX-4");
  await expect(tour.getByRole("status")).toHaveText("해 봤습니다");
  await tour.getByRole("button", { name: "다음" }).click();

  // Update: the same menu as the real list.
  await expect(tour.getByRole("heading")).toHaveText("상태 바꾸기");
  await expect(tour.getByRole("status")).toHaveCount(0);
  await tour.getByRole("button", { name: "EX-4 상태 바꾸기: 백로그" }).click();
  await page.getByRole("menuitemradio", { name: "완료" }).click();
  await expect(tour.getByRole("button", { name: "EX-4 상태 바꾸기: 완료" })).toBeVisible();
  await expect(tour.getByRole("status")).toHaveText("해 봤습니다");
  await tour.getByRole("button", { name: "다음" }).click();

  // Delete.
  await expect(tour.getByRole("heading")).toHaveText("이슈 삭제");
  await tour.getByRole("button", { name: "EX-1 삭제" }).click();
  await expect(rows).toHaveCount(3);
  await tour.getByRole("button", { name: "다음" }).click();

  await expect(tour.getByRole("heading")).toHaveText("내 설정");
  await tour.getByRole("button", { name: "다음" }).click();
  expect((await api<Me>(context, "GET", "/api/v1/me")).tourCompletedAt).toBeNull();
  await tour.getByRole("button", { name: "시작하기" }).click();
  await expect(tour).toHaveCount(0);

  await expect.poll(async () => (await api<Me>(context, "GET", "/api/v1/me")).tourCompletedAt).not.toBeNull();
  // Nothing done to the examples reached the server.
  expect(await api<unknown[]>(context, "GET", "/api/v1/me/issues?scope=created")).toEqual(issuesBefore);
  await page.reload();
  await expect(page.getByRole("navigation").first()).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await context.close();
});

test("skipping is remembered too, and the settings show the tour again without moving the time, on a narrow screen as well", async ({ browser, baseURL }) => {
  const context = await newcomer(browser, baseURL, "skip");
  const page = await context.newPage();
  await page.goto("/issues");

  await page.getByRole("dialog").getByRole("button", { name: "건너뛰기" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect.poll(async () => (await api<Me>(context, "GET", "/api/v1/me")).tourCompletedAt).not.toBeNull();
  const skippedAt = (await api<Me>(context, "GET", "/api/v1/me")).tourCompletedAt;

  await page.goto("/settings");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "둘러보기 다시 보기" }).click();
  await expect(page.getByRole("dialog")).toContainText("Flowbit에 오신 것을 환영합니다");
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect((await api<Me>(context, "GET", "/api/v1/me")).tourCompletedAt).toBe(skippedAt);

  // On a narrow screen there is no sidebar: the steps point at the top bar and stay inside the window.
  // (Shown again from the settings rather than as a third newcomer: sign-ins are limited per minute.)
  const viewport = { width: 360, height: 740 };
  await page.setViewportSize(viewport);
  await page.getByRole("button", { name: "둘러보기 다시 보기" }).click();
  const tour = page.getByRole("dialog");
  for (let step = 0; step < 8; step += 1) {
    await expect(tour).toBeVisible();
    await expect(tour).not.toHaveClass(/opacity-0/);
    const box = (await tour.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
    expect(box.y + box.height).toBeLessThanOrEqual(viewport.height);
    await tour.getByRole("button").last().click();
  }
  await expect(tour).toHaveCount(0);
  await context.close();
});
