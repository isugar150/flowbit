import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { api, logIn, pick, settled, signIn, trackRequests, watchForReloads } from "./support";

/**
 * The everyday actions end to end: making, changing and deleting things from the screen, what a
 * person with the view role may do, and a change by someone else arriving on an open page. These
 * all go through the same two hooks (lib/use-refreshing-mutation.ts), so they are what breaks
 * when those hooks do.
 */

type Issue = { id: string; number: number; key: string; title: string };
type Project = { id: string; name: string };

const run = Date.now().toString(36);
const VIEWER = { loginId: `e2e-viewer-${run}`, password: "e2e-password-9012" };

let context: BrowserContext;
let project: Project;

async function newIssue(title: string, more: Record<string, unknown> = {}): Promise<Issue> {
  return api<Issue>(context, "POST", "/api/v1/issues", {
    title,
    content: null,
    status: "BACKLOG",
    priority: "NONE",
    assigneeIds: [],
    startDate: null,
    dueDate: null,
    milestoneId: null,
    labelIds: [],
    projectId: project.id,
    ...more,
  });
}

async function newPage(from: BrowserContext = context): Promise<Page> {
  const page = await from.newPage();
  trackRequests(page);
  return page;
}

async function openIssue(page: Page, issue: Issue) {
  await page.goto(`/project/${project.id}/issues`);
  await page.locator(`li[data-presence-id="issue-row:${issue.number}"] a`).click();
  await expect(page.getByRole("dialog").getByRole("button", { name: /^상태:/ })).toBeVisible();
}

test.beforeAll(async ({ browser, baseURL }) => {
  context = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await signIn(context);
  project = await api<Project>(context, "POST", "/api/v1/projects", { name: `흐름 ${run}`, description: "" });
  const viewer = await api<{ id: string }>(context, "POST", "/api/v1/admin/members", {
    ...VIEWER,
    displayName: `보기 ${run}`,
    role: "MEMBER",
  });
  await api(context, "PUT", `/api/v1/projects/${project.id}/members/${viewer.id}`, { role: "VIEWER" });
});

test.afterAll(async () => {
  await context.close();
});

test("a new issue is made with C and Ctrl+Enter, and is in the list when the dialog closes", async () => {
  await newIssue("먼저 있던 티켓");
  const page = await newPage();
  await page.goto(`/project/${project.id}/issues`);
  // The one button for it is in the sidebar; on a list it opens that list's dialog.
  await expect(page.getByRole("button", { name: /새 티켓/ })).toBeVisible();
  // The button is in the page as the server sent it; the shortcut works once the scripts have run.
  await settled(page);
  const noReloads = watchForReloads(page);

  await page.keyboard.press("c");
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("textbox", { name: /제목/ }).fill("단축키로 만든 티켓");
  await page.keyboard.press("Control+Enter");

  await expect(dialog).toBeHidden();
  await expect(page.locator("li[data-presence-id^='issue-row:']").filter({ hasText: "단축키로 만든 티켓" })).toBeVisible();
  noReloads();
  await page.close();
});

test("a new project is made from the sidebar and opens", async () => {
  const page = await newPage();
  await page.goto("/");
  const name = `새로 만든 ${run}`;
  await page.getByRole("button", { name: "새 프로젝트" }).first().click();
  await page.locator("#project-name").fill(name);
  await page.getByRole("button", { name: "프로젝트 만들기" }).click();

  await expect(page).toHaveURL(/\/project\/[^/]+$/);
  await expect(page.getByRole("heading", { name, level: 2 })).toBeVisible();
  await expect(page.getByRole("dialog")).toBeHidden();
  // The sidebar has it too.
  await expect(page.getByRole("link", { name }).first()).toBeVisible();
  await page.close();
});

test("milestones are added one after another, renamed from the menu and deleted", async () => {
  const page = await newPage();
  await page.goto(`/project/${project.id}`);
  await page.getByRole("button", { name: "목표 추가" }).click();
  const field = page.getByRole("textbox", { name: "새 목표 이름" });
  await field.fill("알파");
  await field.press("Enter");
  await expect(page.getByRole("button", { name: "알파 열기" })).toBeVisible();
  // The field starts over empty and stays, for the next one.
  await expect(field).toHaveValue("");
  await field.fill("베타");
  await field.press("Enter");
  await expect(page.getByRole("button", { name: "베타 열기" })).toBeVisible();
  await field.press("Escape");

  // "편집" in the line's menu opens the milestone, where its name is changed.
  await page.getByRole("button", { name: "알파 메뉴" }).click();
  await page.getByRole("menuitem", { name: "편집" }).click();
  const name = page.getByRole("dialog").getByRole("textbox", { name: "목표 이름" });
  await name.fill("알파 2");
  await name.press("Enter");
  // The line behind the dialog has the new name at once (it is hidden from assistive tech meanwhile).
  await expect(page.locator("button[data-open-milestone]").filter({ hasText: "알파 2" })).toHaveCount(1);
  await settled(page);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "알파 2 열기" })).toBeVisible();

  await page.getByRole("button", { name: "베타 메뉴" }).click();
  await page.getByRole("menuitem", { name: /삭제/ }).click();
  await expect(page.getByRole("button", { name: "베타 열기" })).toHaveCount(0);
  const names = (await api<{ name: string }[]>(context, "GET", `/api/v1/projects/${project.id}/milestones`)).map((item) => item.name);
  expect(names).toEqual(["알파 2"]);
  await page.close();
});

test("a comment is written, changed and deleted", async () => {
  const issue = await newIssue("댓글 흐름");
  const page = await newPage();
  await openIssue(page, issue);
  const activity = page.locator('[data-presence-id="activity"]');
  const composer = page.locator('[data-presence-id="comment"]');

  await composer.locator(".ProseMirror").click();
  await page.keyboard.type("첫 댓글");
  await page.keyboard.press("Control+Enter");
  const comment = activity.locator("ol > li").filter({ hasText: "첫 댓글" });
  await expect(comment).toBeVisible();
  await expect(composer.locator(".ProseMirror")).toHaveText("");

  await comment.getByRole("button", { name: "댓글 수정" }).click();
  await comment.locator(".ProseMirror").click();
  await page.keyboard.press("End");
  await page.keyboard.type(" 고침");
  await comment.getByRole("button", { name: "저장" }).click();
  await expect(activity.locator("ol > li").filter({ hasText: "첫 댓글 고침" })).toContainText("수정됨");
  await expect(activity.getByRole("button", { name: "저장" })).toHaveCount(0);

  const changed = activity.locator("ol > li").filter({ hasText: "첫 댓글 고침" });
  await changed.getByRole("button", { name: "댓글 삭제" }).click();
  await changed.getByRole("button", { name: /한 번 더/ }).click();
  await expect(activity.locator("ol > li").filter({ hasText: "첫 댓글 고침" })).toHaveCount(0);
  await page.close();
});

test("files are attached and removed", async () => {
  const issue = await newIssue("첨부 흐름");
  const page = await newPage();
  await openIssue(page, issue);
  const section = page.locator('[data-presence-id="attachments"]');

  await section.locator("input[type=file]").setInputFiles([
    { name: "하나.txt", mimeType: "text/plain", buffer: Buffer.from("one") },
    { name: "둘.txt", mimeType: "text/plain", buffer: Buffer.from("two") },
  ]);
  await expect(section.getByText("하나.txt")).toBeVisible();
  await expect(section.getByText("둘.txt")).toBeVisible();
  await expect(section.getByRole("button", { name: "파일 첨부" })).toBeEnabled();

  await section.getByRole("button", { name: "하나.txt 삭제" }).click();
  await expect(section.getByText("하나.txt")).toHaveCount(0);
  await expect(section.getByText("둘.txt")).toBeVisible();
  await page.close();
});

test("an issue is deleted and restored", async () => {
  const issue = await newIssue("삭제 흐름");
  const page = await newPage();
  await openIssue(page, issue);
  const dialog = page.getByRole("dialog");

  await dialog.getByRole("button", { name: "티켓 삭제" }).click();
  await dialog.getByRole("button", { name: /한 번 더/ }).click();
  await expect(dialog.getByRole("status")).toContainText("삭제된 티켓입니다");
  // Read-only now: the status can no longer be picked.
  await expect(dialog.getByRole("button", { name: /^상태:/ })).toBeDisabled();

  await dialog.getByRole("button", { name: "복원" }).click();
  await expect(dialog.getByRole("status")).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: /^상태:/ })).toBeEnabled();
  await page.close();
});

test("a label is made on the spot and put on the issue", async () => {
  const issue = await newIssue("라벨 흐름");
  const page = await newPage();
  await openIssue(page, issue);
  const dialog = page.getByRole("dialog");
  const name = `라벨-${run}`;

  await dialog.getByRole("button", { name: /^라벨 고르기/ }).click();
  await page.getByPlaceholder("라벨 찾기 또는 추가").fill(name);
  await page.getByText(`"${name}" 라벨 만들기`).click();
  await page.keyboard.press("Escape");

  await expect(dialog.locator('[data-presence-id="property:라벨"]')).toContainText(name);
  await settled(page);
  const saved = await api<{ labels: { name: string }[] }>(context, "GET", `/api/v1/issues/${issue.number}`);
  expect(saved.labels.map((label) => label.name)).toEqual([name]);
  await expect(dialog.locator('[data-presence-id="property:라벨"]')).toContainText(name);
  await page.close();
});

test("moving an issue to no project drops its milestone and keeps the rest", async () => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, {
    name: "옮기기 전 목표",
    targetDate: null,
  });
  const issue = await newIssue("프로젝트 바꾸기", { milestoneId: milestone.id, priority: "HIGH" });
  const page = await newPage();
  await openIssue(page, issue);
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("button", { name: "목표: 옮기기 전 목표" })).toBeVisible();

  await pick(page, dialog.getByRole("button", { name: /^프로젝트:/ }), "프로젝트 없음");

  await expect(dialog.getByRole("button", { name: "프로젝트: 프로젝트 없음" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: /^목표:/ })).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: "우선순위: 높음" })).toBeVisible();
  await settled(page);
  const saved = await api<{ project: unknown; milestone: unknown }>(context, "GET", `/api/v1/issues/${issue.number}`);
  expect(saved).toMatchObject({ project: null, milestone: null });
  await page.close();
});

test("the view role sees everything and can change nothing", async ({ browser, baseURL }) => {
  const issue = await newIssue("보기 권한");
  const viewer = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await logIn(viewer, VIEWER);
  const page = await newPage(viewer);
  await page.goto(`/project/${project.id}/issues`);
  const row = page.locator(`li[data-presence-id="issue-row:${issue.number}"]`);
  await expect(row).toBeVisible();
  await expect(row.getByRole("button", { name: /상태 바꾸기/ })).toHaveCount(0);

  await row.locator("a").click();
  const dialog = page.getByRole("dialog");
  for (const name of [/^상태:/, /^우선순위:/, /^담당자:/, /^프로젝트:/]) {
    await expect(dialog.getByRole("button", { name })).toBeDisabled();
  }
  await expect(dialog.locator('[data-presence-id="comment"]')).toHaveCount(0);

  await page.goto(`/project/${project.id}`);
  await expect(page.getByRole("button", { name: /^상태:/ })).toBeDisabled();
  await expect(page.getByRole("button", { name: "목표 추가" })).toHaveCount(0);
  await viewer.close();
});

test("a change by someone else arrives on an open issue and on the list behind it", async ({ browser, baseURL }) => {
  const issue = await newIssue("실시간 반영");
  const viewer = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await logIn(viewer, VIEWER);
  const theirs = await newPage(viewer);
  await openIssue(theirs, issue);
  const noReloads = watchForReloads(theirs);
  await expect(theirs.getByRole("dialog").getByRole("button", { name: "상태: 백로그" })).toBeVisible();

  const mine = await newPage();
  await openIssue(mine, issue);
  await pick(mine, mine.getByRole("dialog").getByRole("button", { name: /^상태:/ }), "완료");
  await mine.getByRole("dialog").getByRole("textbox", { name: /제목/ }).fill("실시간 반영됨");
  await mine.getByRole("dialog").getByRole("textbox", { name: /제목/ }).press("Enter");

  await expect(theirs.getByRole("dialog").getByRole("button", { name: "상태: 완료" })).toBeVisible();
  await expect(theirs.getByRole("dialog").getByRole("heading", { name: "실시간 반영됨", exact: true })).toBeVisible();
  const row = theirs.locator(`li[data-presence-id="issue-row:${issue.number}"]`);
  await expect(row).toContainText("실시간 반영됨");
  expect(await row.evaluate((line) => line.closest("section")?.getAttribute("aria-label"))).toBe("완료");
  noReloads();
  await viewer.close();
  await mine.close();
});
