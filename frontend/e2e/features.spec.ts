import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { api, logIn, settled, signIn, trackRequests } from "./support";

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

const blank = { content: null, status: "BACKLOG", priority: "NONE", assigneeIds: [], startDate: null, dueDate: null, milestoneId: null, labelIds: [] };

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
  await newIssue("목표 안의 끝난 티켓", { milestoneId: milestone.id, status: "DONE" });
  const open = await newIssue("목표 안의 남은 티켓", { milestoneId: milestone.id });
  // A sub-issue starts in its parent's milestone.
  await newIssue("남은 티켓의 하위 티켓", { parentId: open.id, status: "DONE" });
  await newIssue("목표 밖의 티켓");
  const page = await newPage();
  await page.goto(`/project/${project.id}`);
  const line = page.locator(`li[data-presence-id="milestone:${milestone.id}"]`);
  await expect(line).toContainText("67%");
  await expect(line).toContainText("티켓 2/3");
  // Nothing on the line is a field: it cannot be changed by accident.
  await expect(line.locator("input")).toHaveCount(0);

  // Anywhere on the line opens it, not only the name.
  await line.click({ position: { x: 400, y: 30 } });
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("textbox", { name: "목표 이름" })).toHaveValue("베타 출시");
  const issues = dialog.getByRole("complementary");
  // What is still to do comes before what is done, and a sub-issue sits under its parent, one step in.
  await expect(issues.getByRole("listitem")).toContainText(["목표 안의 남은 티켓", "남은 티켓의 하위 티켓", "목표 안의 끝난 티켓"]);
  await expect(issues.getByRole("listitem").nth(1).locator("span[aria-hidden='true']").first()).toHaveCSS("width", "20px");
  await expect(issues).not.toContainText("목표 밖의 티켓");

  // The comment box under the notes is an editor too, so the notes are asked for by name.
  await dialog.getByRole("textbox", { name: "목표 정리" }).click();
  await page.keyboard.type("QA 는 다음 주에 끝난다");
  await expect(dialog.getByText("저장됨")).toBeVisible();

  // An issue opens over the milestone and can be changed there; closing it returns to the milestone.
  await issues.getByRole("link", { name: "목표 안의 남은 티켓" }).click();
  // The milestone has a 상태 chip too (D-163), so the issue's dialog is the one with an issue title.
  const issueDialog = page.getByRole("dialog").filter({ has: page.getByRole("textbox", { name: "제목", exact: true }) });
  await issueDialog.getByRole("button", { name: /^상태:/ }).click();
  await page.getByRole("menuitemradio").filter({ hasText: "완료" }).click();
  await expect(issueDialog.getByRole("button", { name: "상태: 완료" })).toBeVisible();
  await settled(page);
  await page.keyboard.press("Escape");
  await expect(issueDialog).toHaveCount(0);
  await expect(page.getByRole("dialog").getByRole("textbox", { name: "목표 이름" })).toBeVisible();
  // The list behind shows the change without being opened again: the issue is done now, so it
  // (with its sub-issue) no longer comes before the one that already was.
  await expect(issues.getByRole("listitem")).toContainText(["목표 안의 끝난 티켓", "목표 안의 남은 티켓", "남은 티켓의 하위 티켓"]);

  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(line).toContainText("100%");
  expect((await api<{ status: string }>(context, "GET", `/api/v1/issues/${open.number}`)).status).toBe("DONE");
  await page.close();
});

test("a milestone's issues show two levels of sub-issues; the deeper ones are counted on one line (D-142)", async () => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "깊은 목표", targetDate: null });
  // Five levels: the top one and four under it, each the only sub-issue of the one before.
  const top = await newIssue("깊이 0", { milestoneId: milestone.id });
  let parent = top;
  for (const depth of [1, 2, 3, 4]) parent = await newIssue(`깊이 ${depth}`, { parentId: parent.id });
  const page = await newPage();
  await page.goto(`/project/${project.id}`);
  await page.locator(`li[data-presence-id="milestone:${milestone.id}"]`).click();
  const issues = page.getByRole("dialog").getByRole("complementary");
  await expect(issues).toContainText("티켓5");
  await expect(issues.getByRole("listitem")).toHaveText([/깊이 0/, /깊이 1/, /깊이 2/, "외 2개"]);
  // The count is only words, not a link.
  await expect(issues.getByRole("listitem").last().getByRole("link")).toHaveCount(0);
  await page.close();
});

test("beside a ticket its milestone lists ten tickets; the rest are counted on one line (D-143)", async () => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "많은 목표", targetDate: null });
  const first = await newIssue("많은 티켓 1", { milestoneId: milestone.id });
  for (let n = 2; n <= 12; n++) await newIssue(`많은 티켓 ${n}`, { milestoneId: milestone.id });
  const page = await newPage();
  await page.goto(`/issue/${first.key}`);
  const panel = page.getByRole("complementary", { name: "많은 목표 목표" });
  const items = panel.getByRole("listitem");
  await expect(items).toHaveCount(11);
  await expect(items.last()).toHaveText("외 2개");
  await page.close();
});

test("the milestone dialog lists ten tickets too; the rest are counted on one line (D-184)", async () => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "열 개 목표", targetDate: null });
  for (let n = 1; n <= 12; n++) await newIssue(`열 개 티켓 ${n}`, { milestoneId: milestone.id });
  const page = await newPage();
  await page.goto(`/project/${project.id}`);
  await page.locator(`li[data-presence-id="milestone:${milestone.id}"]`).click();
  // The first list in the right column is the tickets; the properties above it are not a list.
  const items = page.getByRole("dialog").getByRole("complementary").getByRole("list").first().getByRole("listitem");
  await expect(items).toHaveCount(11);
  await expect(items.last()).toHaveText("외 2개");
  await page.close();
});

test("a milestone is given an assignee in its dialog; the overview line shows who has it (D-185)", async () => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "담당 목표", targetDate: null });
  const page = await newPage();
  await page.goto(`/project/${project.id}`);
  await page.locator(`li[data-presence-id="milestone:${milestone.id}"]`).click();
  const dialog = page.getByRole("dialog");
  // The people are ticked in a list that stays open for more; Escape closes it (as for an issue).
  await dialog.getByRole("button", { name: /^담당자:/ }).click();
  await page.getByRole("option").filter({ hasText: `편집 ${run}` }).click();
  await page.keyboard.press("Escape");
  await expect(dialog.getByRole("button", { name: `담당자: 편집 ${run}` })).toBeVisible();
  await settled(page);
  const saved = await api<{ id: string; assignees: { loginId: string }[] }[]>(context, "GET", `/api/v1/projects/${project.id}/milestones`);
  expect(saved.find((item) => item.id === milestone.id)?.assignees.map((person) => person.loginId)).toEqual([EDITOR.loginId]);
  await page.close();
});

test("a property added to a project by name and kind is filled in place, and read by the one who only reads (D-186)", async ({ browser, baseURL }) => {
  const own = await api<{ id: string }>(context, "POST", "/api/v1/projects", { name: `속성 ${run}`, description: "" });
  await api(context, "PUT", `/api/v1/projects/${own.id}/members/${editorId}`, { role: "VIEWER" });
  const page = await newPage();
  await page.goto(`/project/${own.id}`);
  await page.getByRole("button", { name: "속성 추가" }).click();
  await page.getByRole("textbox", { name: "속성 이름" }).fill("배포 메모");
  // The kind is picked from the app's menu; 텍스트 is where it starts, and 날짜 is chosen and then changed back.
  await page.getByRole("button", { name: "값 형식: 텍스트" }).click();
  await page.getByRole("menuitemradio", { name: "날짜" }).click();
  await page.getByRole("button", { name: "값 형식: 날짜" }).click();
  await page.getByRole("menuitemradio", { name: "텍스트" }).click();
  await page.getByRole("button", { name: "추가", exact: true }).click();
  // The new property is there, empty; its value is typed in place and saved when the field is left.
  const field = page.getByRole("textbox", { name: "배포 메모 값" });
  await field.fill("금요일 저녁");
  await field.press("Enter");
  await settled(page);
  const read = await api<{ canEdit: boolean; items: { label: string; type: string; value: string | null }[] }>(
    context,
    "GET",
    `/api/v1/custom-properties/project/${own.id}`,
  );
  expect(read.items).toMatchObject([{ label: "배포 메모", type: "TEXT", value: "금요일 저녁" }]);
  await page.close();

  // Someone who only reads sees the value but has nothing to change it with.
  const viewer = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await logIn(viewer, EDITOR);
  const reading = await newPage(viewer);
  await reading.goto(`/project/${own.id}`);
  // Exactly: the activity under the page says "없음 → 금요일 저녁".
  await expect(reading.getByText("금요일 저녁", { exact: true })).toBeVisible();
  await expect(reading.getByRole("textbox", { name: "배포 메모 값" })).toHaveCount(0);
  await expect(reading.getByRole("button", { name: "속성 추가" })).toHaveCount(0);
  await viewer.close();
});

test("a milestone's dialog shows, at its top right, who has it open now and who looked before (D-188)", async ({ browser, baseURL }) => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "본 사람 목표", targetDate: null });
  const guest = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await logIn(guest, EDITOR);
  const open = async (page: Page) => {
    await page.goto(`/project/${project.id}`);
    await page.locator(`li[data-presence-id="milestone:${milestone.id}"]`).click();
    await expect(page.getByRole("dialog").getByRole("button", { name: /^상태:/ })).toBeVisible();
    await settled(page);
  };
  const watching = await newPage();
  await open(watching);
  const dialog = watching.getByRole("dialog");
  // Nobody else has been here: no one but the one looking, who is the dialog's own page.
  await expect(dialog.getByLabel(`편집 ${run}`)).toHaveCount(0);

  const visiting = await newPage(guest);
  await open(visiting);
  // Now, in the bar above the milestone, next to the close button.
  await expect(dialog.getByRole("group", { name: /지금 보고 있는 사람/ }).getByLabel(`편집 ${run} · 지금 보는 중`)).toBeVisible();

  // The visitor closes it with the close button; they stay, dimmed, with when they last looked.
  await visiting.getByRole("dialog").getByRole("button", { name: "닫기" }).click();
  await expect(visiting.getByRole("dialog")).toHaveCount(0);
  const left = dialog.getByLabel(`편집 ${run}`, { exact: true });
  await expect(left).toBeVisible();
  await left.hover();
  await expect(watching.getByText("마지막 확인")).toBeVisible();
  await guest.close();
  await watching.close();
});

test("the editor role opens a milestone; the view of it is the same without the menu for those who only read", async ({ browser, baseURL }) => {
  const milestone = await api<{ id: string }>(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "읽기 확인", targetDate: "2026-12-24" });
  await api(context, "PUT", `/api/v1/projects/${project.id}/members/${editorId}`, { role: "VIEWER" });
  const viewer = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await logIn(viewer, EDITOR);
  const page = await newPage(viewer);
  await page.goto(`/project/${project.id}`);
  const line = page.locator(`li[data-presence-id="milestone:${milestone.id}"]`);
  await expect(line).toContainText("12-24");
  await expect(line.getByRole("button", { name: "읽기 확인 메뉴" })).toHaveCount(0);

  await line.getByRole("link", { name: "읽기 확인 열기" }).click();
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
  await newIssue("아카이브와 함께 숨는 티켓 " + run, {}, doomed.id);
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
  await expect(page.getByText(`아카이브와 함께 숨는 티켓 ${run}`)).toHaveCount(0);
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
  await expect(page.getByText(`아카이브와 함께 숨는 티켓 ${run}`)).toBeVisible();
  await page.close();
});

test("only those who manage a project can archive or restore it", async ({ browser, baseURL }) => {
  const name = `남의 프로젝트 ${run}`;
  const theirs = await api<Project>(context, "POST", "/api/v1/projects", { name, description: "" });
  await api(context, "PUT", `/api/v1/projects/${theirs.id}/members/${editorId}`, { role: "EDITOR" });
  const editor = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await logIn(editor, EDITOR);
  const page = await newPage(editor);
  // Settings are for the managers only (D-131): an editor is sent to the overview, which has no settings tab.
  await page.goto(`/project/${theirs.id}/settings`);
  await expect(page).toHaveURL(new RegExp(`/project/${theirs.id}$`));
  await expect(page.getByRole("link", { name: "설정" })).toHaveCount(0);
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
  await logIn(editor, EDITOR);
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
  await dialog.getByText("요청자").click();
  await expect.poll(marked).toEqual([]);
  await editor.close();
  await acting.close();
});
