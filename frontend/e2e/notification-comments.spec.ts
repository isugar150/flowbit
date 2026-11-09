import { expect, test, type BrowserContext, type Locator, type Page } from "@playwright/test";
import { api, logIn, signIn } from "./support";

type Issue = { id: string; number: number; key: string };
type Comment = { id: string };
type Alert = { id: string; path: string; completed: boolean; read: boolean };

const paragraph = (text: string) => ({ type: "paragraph", content: [{ type: "text", text }] });
const content = (text: string) => ({ type: "doc", content: [paragraph(text)] });
let admin: BrowserContext;
let member: BrowserContext;
let observer: BrowserContext;
let projectId: string;
let memberIds: string[];

async function alertFor(context: BrowserContext, commentId: string) {
  const inbox = await api<{ items: Alert[] }>(context, "GET", "/api/v1/me/inbox?limit=100");
  return inbox.items.find((item) => item.path.endsWith(`#comment-${commentId}`));
}

async function visibleInScrollBox(target: Locator) {
  await expect.poll(() => target.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    let box = element.parentElement;
    while (box && !(box.scrollHeight > box.clientHeight + 4 && /auto|scroll/.test(getComputedStyle(box).overflowY))) box = box.parentElement;
    const viewport = box?.getBoundingClientRect();
    return !!viewport && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom;
  })).toBe(true);
}

async function openNotification(page: Page, commentId: string) {
  await page.getByRole("button", { name: /^알림(?:,|$)/ }).click();
  await expect(page.getByRole("dialog").getByRole("button", { name: "미처리", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("dialog").getByRole("button", { name: "전체", exact: true }).click();
  await page.getByRole("dialog").locator(`a[href$="#comment-${commentId}"]`).click();
  const target = page.locator(`[data-comment-id="${commentId}"]`);
  await expect(target).toBeVisible();
  await expect(target).toHaveClass(/comment-focus/);
  await visibleInScrollBox(target);
  return target;
}

async function fixture() {
  const run = Date.now().toString(36);
  const issue = await api<Issue>(admin, "POST", "/api/v1/issues", {
    title: `알림 댓글 이동 ${run}`, projectId, assigneeIds: memberIds,
    content: { type: "doc", content: Array.from({ length: 30 }, (_, index) => paragraph(`긴 본문 ${index}`)) },
  });
  const root = await api<Comment>(admin, "POST", `/api/v1/issues/${issue.number}/comments`, { content: content(`원 댓글 ${run}`) });
  const reply = await api<Comment>(member, "POST", `/api/v1/issues/${issue.number}/comments`, { content: content(`답글 ${run}`), parentId: root.id });
  const other = await api<Comment>(admin, "POST", `/api/v1/issues/${issue.number}/comments`, { content: content(`다른 대화 ${run}`) });
  await expect.poll(() => alertFor(observer, root.id)).toBeTruthy();
  await expect.poll(() => alertFor(admin, reply.id)).toBeTruthy();
  return { issue, root, reply, other };
}

test.beforeAll(async ({ browser, baseURL }) => {
  admin = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await signIn(admin);
  projectId = (await api<{ id: string }>(admin, "POST", "/api/v1/projects", { name: `알림 검증 ${Date.now()}` })).id;
  memberIds = [];
  const contexts: BrowserContext[] = [];
  for (const name of ["member", "observer"]) {
    const loginId = `notification-${name}-${Date.now().toString(36)}`;
    const password = "e2e-notification-1234";
    const user = await api<{ id: string }>(admin, "POST", "/api/v1/admin/members", { loginId, password, role: "MEMBER" });
    memberIds.push(user.id);
    await api(admin, "PUT", `/api/v1/projects/${projectId}/members/${user.id}`, { role: "EDITOR" });
    const context = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
    await logIn(context, { loginId, password });
    contexts.push(context);
  }
  [member, observer] = contexts;
});

test.afterAll(async () => {
  await Promise.all([admin, member, observer].filter(Boolean).map((context) => context.close()));
});

test("실제 티켓 모달에서 댓글로 이동하고 해결하면 모든 수신자의 대화 알림을 완료한다", async () => {
  const { root, reply, other } = await fixture();
  const page = await member.newPage();
  const observerRead = (await alertFor(observer, root.id))?.read;
  await page.goto("/issues");
  const target = await openNotification(page, root.id);
  await target.getByRole("button", { name: "해결", exact: true }).click();
  await expect.poll(async () => (await alertFor(observer, root.id))?.completed).toBe(true);
  await expect.poll(async () => (await alertFor(member, root.id))?.completed).toBe(true);
  await expect.poll(async () => (await alertFor(admin, reply.id))?.completed).toBe(true);
  expect((await alertFor(observer, root.id))?.read).toBe(observerRead);
  expect((await alertFor(observer, other.id))?.completed).toBe(false);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/\/issues$/);
  const reopened = await openNotification(page, root.id);
  await expect(reopened).toHaveAttribute("data-resolved", "true");
  await expect(reopened.getByRole("button", { name: "해결된 댓글 접기", exact: true })).toBeVisible();
  await page.close();
});

test("좁은 화면의 직접 링크도 이전 활동의 해결된 부모를 펼쳐 답글을 강조한다", async () => {
  const { issue, root, reply } = await fixture();
  for (let index = 0; index < 18; index++) {
    await api(admin, "POST", `/api/v1/issues/${issue.number}/comments`, { content: content(`새 활동 ${index}`) });
  }
  await api(admin, "POST", `/api/v1/comments/${root.id}/resolution`);
  const page = await admin.newPage();
  await page.setViewportSize({ width: 360, height: 780 });
  await page.goto(`/issue/${issue.key}#comment-${reply.id}`);
  const target = page.locator(`[data-comment-id="${reply.id}"]`);
  await expect(target).toBeVisible();
  await expect(target).toHaveClass(/comment-focus/);
  await visibleInScrollBox(target);
  await expect(page.locator(`[data-comment-id="${root.id}"]`)).toHaveAttribute("data-resolved", "true");
  await page.close();
});
