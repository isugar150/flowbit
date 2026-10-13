import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { api, settled, signIn, trackRequests } from "./support";

/**
 * Milestones on the project overview (a line opens the milestone: its notes and its issues),
 * archiving a project and restoring it, and what others see of where a person is working
 * (only what they clicked or have the keyboard in, never what the pointer passes over).
 */

type Issue = { id: string; number: number; key: string; title: string };
type Project = { id: string; name: string };

const run = Date.now().toString(36);
const EDITOR = { loginId: `e2e-editor-${run}`, password: "e2e-password-3456" };

let context: BrowserContext;
let project: Project;
let editorId: string;

const blank = { content: null, status: "BACKLOG", priority: "NONE", assigneeId: null, startDate: null, dueDate: null, milestoneId: null, labelIds: [] };

async function newIssue(title: string, more: Record<string, unknown> = {}, projectId = project.id): Promise<Issue> {
  return api<Issue>(context, "POST", "/api/v1/issues", { ...blank, title, projectId, ...more });
}

async function newPage(from: BrowserContext = context): Promise<Page> {
  const page = await from.newPage();
  trackRequests(page);
  return page;
}

test.beforeAll(async ({ browser, baseURL }) => {
  context = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await signIn(context);
  project = await api<Project>(context, "POST", "/api/v1/projects", { name: `기능 ${run}`, description: "" });
  const editor = await api<{ id: string }>(context, "POST", "/api/v1/admin/members", { ...EDITOR, displayName: `편집 ${run}`, role: "MEMBER" });
  editorId = editor.id;
  await api(context, "PUT", `/api/v1/projects/${project.id}/members/${editorId}`, { role: "EDITOR" });
});

test.afterAll(async () => {
  await context.close();
});

test("a milestone line opens the milestone: its issues on the right, notes on the left", async () => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "베타 출시", targetDate: null });
  await newIssue("목표 안의 끝난 이슈", { milestoneId: milestone.id, status: "DONE" });
  const open = await newIssue("목표 안의 남은 이슈", { milestoneId: milestone.id });
  await newIssue("목표 밖의 이슈");
  const page = await newPage();
  await page.goto(`/project/${project.id}`);
  const line = page.locator(`li[data-presence-id="milestone:${milestone.id}"]`);
  await expect(line).toContainText("50%");
  await expect(line).toContainText("이슈 1/2");
  // Nothing on the line is a field: it cannot be changed by accident.
  await expect(line.locator("input")).toHaveCount(0);

  // Anywhere on the line opens it, not only the name.
  await line.click({ position: { x: 400, y: 30 } });
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("textbox", { name: "목표 이름" })).toHaveValue("베타 출시");
  const issues = dialog.getByRole("complementary");
  await expect(issues.getByRole("listitem")).toHaveCount(2);
  // What is still to do comes before what is done.
  await expect(issues.getByRole("listitem").first()).toContainText("목표 안의 남은 이슈");
  await expect(issues).not.toContainText("목표 밖의 이슈");

  await dialog.locator(".ProseMirror").click();
  await page.keyboard.type("QA 는 다음 주에 끝난다");
  await expect(dialog.getByText("저장됨")).toBeVisible();

  // An issue opens over the milestone and can be changed there; closing it returns to the milestone.
  await issues.getByRole("link", { name: "목표 안의 남은 이슈" }).click();
  const issueDialog = page.getByRole("dialog").filter({ has: page.getByRole("button", { name: /^상태:/ }) });
  await issueDialog.getByRole("button", { name: /^상태:/ }).click();
  await page.getByRole("menuitemradio").filter({ hasText: "완료" }).click();
  await expect(issueDialog.getByRole("button", { name: "상태: 완료" })).toBeVisible();
  await settled(page);
  await page.keyboard.press("Escape");
  await expect(issueDialog).toHaveCount(0);
  await expect(page.getByRole("dialog").getByRole("textbox", { name: "목표 이름" })).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(line).toContainText("100%");
  await expect(line).toContainText("정리 있음");
  expect((await api<{ status: string }>(context, "GET", `/api/v1/issues/${open.number}`)).status).toBe("DONE");
  await page.close();
});

test("the editor role opens a milestone; the view of it is the same without the menu for those who only read", async ({ browser, baseURL }) => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "읽기 확인", targetDate: "2026-12-24" });
  await api(context, "PUT", `/api/v1/projects/${project.id}/members/${editorId}`, { role: "VIEWER" });
  const viewer = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await api(viewer, "POST", "/api/v1/auth/login", EDITOR);
  const page = await newPage(viewer);
  await page.goto(`/project/${project.id}`);
  const line = page.locator(`li[data-presence-id="milestone:${milestone.id}"]`);
  await expect(line).toContainText("12-24");
  await expect(line.getByRole("button", { name: "읽기 확인 메뉴" })).toHaveCount(0);

  await line.getByRole("button", { name: "읽기 확인 열기" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "읽기 확인", level: 2 }).last()).toBeVisible();
  await expect(dialog.getByRole("textbox", { name: "목표 이름" })).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: /^목표일:/ })).toBeDisabled();
  await viewer.close();
  await api(context, "PUT", `/api/v1/projects/${project.id}/members/${editorId}`, { role: "EDITOR" });
});

test("a project is archived from its settings, shows greyed under 아카이브됨, and is restored", async () => {
  const name = `치울 프로젝트 ${run}`;
  const doomed = await api<Project>(context, "POST", "/api/v1/projects", { name, description: "" });
  await newIssue("아카이브와 함께 숨는 이슈", {}, doomed.id);
  const page = await newPage();
  await page.goto(`/project/${doomed.id}/settings`);

  await page.getByRole("button", { name: "프로젝트 아카이브" }).click();
  await page.getByRole("button", { name: "아카이브하기" }).click();

  await expect(page).toHaveURL(/\/archived$/);
  const row = page.getByRole("row").filter({ hasText: name });
  await expect(row).toBeVisible();
  // Greyed, and not a way in.
  await expect(row.getByRole("link")).toHaveCount(0);
  await expect(row).toHaveClass(/text-muted-foreground/);
  await expect(page.getByRole("navigation", { name: "프로젝트", exact: true }).getByRole("link", { name })).toHaveCount(0);

  await page.getByRole("link", { name: "전체 프로젝트" }).last().click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("row").filter({ hasText: name })).toHaveCount(0);
  await page.goto("/issues");
  await expect(page.getByText("아카이브와 함께 숨는 이슈")).toHaveCount(0);
  // Its address leads nowhere while it is put away.
  await page.goto(`/project/${doomed.id}`);
  await expect(page.getByText("404")).toBeVisible();
  await expect(page.getByRole("heading", { name })).toHaveCount(0);

  await page.goto("/");
  await page.getByRole("link", { name: "아카이브됨" }).click();
  await page.getByRole("button", { name: `${name} 복원` }).click();
  await expect(page.getByRole("row").filter({ hasText: name })).toHaveCount(0);
  await page.getByRole("link", { name: "전체 프로젝트" }).last().click();
  await expect(page.getByRole("row").filter({ hasText: name }).getByRole("link", { name })).toBeVisible();
  await page.goto("/issues");
  await expect(page.getByText("아카이브와 함께 숨는 이슈")).toBeVisible();
  await page.close();
});

test("only those who manage a project can archive or restore it", async ({ browser, baseURL }) => {
  const name = `남의 프로젝트 ${run}`;
  const theirs = await api<Project>(context, "POST", "/api/v1/projects", { name, description: "" });
  await api(context, "PUT", `/api/v1/projects/${theirs.id}/members/${editorId}`, { role: "EDITOR" });
  const editor = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await api(editor, "POST", "/api/v1/auth/login", EDITOR);
  const page = await newPage(editor);
  await page.goto(`/project/${theirs.id}/settings`);
  await expect(page.getByRole("textbox", { name: "이름" })).toBeVisible();
  await expect(page.getByRole("button", { name: "프로젝트 아카이브" })).toHaveCount(0);

  await api(context, "POST", `/api/v1/projects/${theirs.id}/archive`);
  await page.goto("/archived");
  const row = page.getByRole("row").filter({ hasText: name });
  await expect(row).toBeVisible();
  await expect(row.getByRole("button", { name: /복원/ })).toHaveCount(0);
  await editor.close();
  await api(context, "POST", `/api/v1/projects/${theirs.id}/restore`);
});

test("others see where a person clicked or types, not where their pointer passes", async ({ browser, baseURL }) => {
  const issue = await newIssue("하이라이트 확인");
  const editor = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await api(editor, "POST", "/api/v1/auth/login", EDITOR);
  const watching = await newPage(editor);
  const acting = await newPage();
  for (const page of [watching, acting]) {
    await page.goto(`/project/${project.id}/issues`);
    await page.locator(`li[data-presence-id="issue-row:${issue.number}"] a`).click();
    await expect(page.getByRole("dialog").getByRole("button", { name: /^상태:/ })).toBeVisible();
    await settled(page);
  }
  const marked = () => watching.locator("[data-presence-by]").evaluateAll((items) => items.map((item) => item.getAttribute("data-presence-id")));
  const dialog = acting.getByRole("dialog");

  // The pointer rests on one part after another: nothing lights up for the other person.
  for (const part of ['[data-presence-id="property:우선순위"]', '[data-presence-id="title"]', '[data-presence-id="reactions"]']) {
    await dialog.locator(part).hover();
    await acting.waitForTimeout(500);
    expect(await marked()).toEqual([]);
  }

  // Clicking into the title: that part is theirs.
  await dialog.getByRole("textbox", { name: /제목/ }).click();
  await expect.poll(marked).toEqual(["title"]);

  // Moving on with the keyboard and typing: the body, at the caret.
  await dialog.locator('[data-presence-id="body"] .ProseMirror').click();
  await acting.keyboard.type("본문");
  await expect.poll(() => watching.locator('[data-presence-id="body"] > div[style*="position: absolute"]').count()).toBe(1);
  expect(await marked()).toEqual([]);

  // Opening a property's menu: that line.
  await dialog.getByRole("button", { name: /^우선순위:/ }).click();
  await expect.poll(marked).toEqual(["property:우선순위"]);
  await acting.keyboard.press("Escape");

  // Clicking where there is nothing to work on clears it.
  await dialog.getByText("만든 사람").click();
  await expect.poll(marked).toEqual([]);
  await editor.close();
  await acting.close();
});
