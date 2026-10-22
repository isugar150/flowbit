import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { api, logIn, settled, signIn, trackRequests } from "./support";

/**
 * The properties people add to a project or a ticket, the ticket a ticket hangs under (found by searching, with the
 * path from the top), archiving a project and restoring it, and what others see of where a person is working
 * (only what they clicked or have the keyboard in, never what the pointer passes over).
 */

type Issue = { id: string; number: number; key: string; title: string };
type Project = { id: string; name: string };

const run = Date.now().toString(36);
const EDITOR = { loginId: `e2e-editor-${run}`, password: "e2e-password-3456" };

let context: BrowserContext;
let project: Project;
let editorId: string;

const blank = { content: null, status: "BACKLOG", priority: "NONE", assigneeIds: [], startDate: null, dueDate: null, labelIds: [] };

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

test("a ticket's added properties continue its 속성 group, and 속성 추가 stands right under the due date (D-189)", async () => {
  const issue = await newIssue("속성 합치기 " + run);
  const page = await newPage();
  await page.goto(`/issue/${issue.key}`);
  const dialog = page.getByRole("dialog");
  const group = dialog.locator("section").filter({ has: page.getByRole("heading", { name: "속성", exact: true }) });
  await expect(dialog.getByRole("button", { name: /^상태:/ })).toBeVisible();
  // They have no group of their own.
  await expect(dialog.getByRole("heading", { name: "사용자 속성" })).toHaveCount(0);

  const due = group.locator('[data-presence-id="property:기한"]');
  const add = group.getByRole("button", { name: "속성 추가" });
  await expect(add).toBeVisible();
  const dueBox = (await due.boundingBox())!;
  const addBox = (await add.boundingBox())!;
  expect(addBox.y).toBeGreaterThanOrEqual(dueBox.y + dueBox.height - 1);
  // Nothing but the way to add one is between the due date and the labels.
  const labels = (await dialog.getByRole("heading", { name: "라벨", exact: true }).boundingBox())!;
  expect(addBox.y).toBeLessThan(labels.y);

  await add.click();
  await page.getByRole("textbox", { name: "속성 이름" }).fill("배포예정일");
  await page.getByRole("button", { name: "값 형식: 텍스트" }).click();
  await page.getByRole("menuitemradio", { name: "날짜" }).click();
  await page.getByRole("button", { name: "추가", exact: true }).click();

  // The new property is a row of the same group, under the due date, and the way to add another is under it.
  const label = group.getByText("배포예정일", { exact: true });
  await expect(label).toBeVisible();
  await settled(page);
  const labelBox = (await label.boundingBox())!;
  const addAgain = (await group.getByRole("button", { name: "속성 추가" }).boundingBox())!;
  expect(labelBox.y).toBeGreaterThan(dueBox.y);
  expect(addAgain.y).toBeGreaterThan(labelBox.y);
  expect(addAgain.y).toBeLessThan((await dialog.getByRole("heading", { name: "라벨", exact: true }).boundingBox())!.y);
  const read = await api<{ items: { label: string; type: string }[] }>(context, "GET", `/api/v1/custom-properties/issue/${issue.id}`);
  expect(read.items).toMatchObject([{ label: "배포예정일", type: "DATE" }]);
  await page.close();
});

test("a ticket is put under another by searching, and the search shows the path from the top (D-189)", async () => {
  const root = await newIssue(`경로 맨 위 ${run}`);
  const middle = await newIssue(`경로 가운데 ${run}`, { parentId: root.id });
  const alone = await newIssue(`경로 밖의 티켓 ${run}`);
  const page = await newPage();
  await page.goto(`/issue/${alone.key}`);
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("button", { name: /^상태:/ })).toBeVisible();
  await settled(page);

  await dialog.getByRole("button", { name: "상위 티켓 고르기" }).click();
  await page.getByRole("combobox", { name: "상위 티켓으로 삼을 티켓 찾기" }).fill(`가운데 ${run}`);
  const found = page.getByRole("option").filter({ hasText: middle.key });
  // The ticket found hangs under another: its path from the top is under it.
  await expect(found).toContainText(root.key);
  await expect(found).toContainText(`경로 맨 위 ${run}`);
  // The ticket itself is not a candidate.
  await page.getByRole("combobox", { name: "상위 티켓으로 삼을 티켓 찾기" }).fill(`밖의 티켓 ${run}`);
  await expect(page.getByText("에 맞는 티켓이 없습니다")).toBeVisible();

  await page.getByRole("combobox", { name: "상위 티켓으로 삼을 티켓 찾기" }).fill(`가운데 ${run}`);
  await found.click();
  await settled(page);
  await expect(dialog.getByRole("button", { name: "상위 티켓 고르기" })).toContainText(middle.key);
  const saved = await api<{ parent: { key: string } | null; ancestors: { key: string }[] }>(context, "GET", `/api/v1/issues/${alone.number}`);
  expect(saved.parent?.key).toBe(middle.key);
  expect(saved.ancestors.map((item) => item.key)).toEqual([root.key, middle.key]);

  // Taken off again.
  await dialog.getByRole("button", { name: "상위 티켓 고르기" }).click();
  await page.getByRole("button", { name: "상위 티켓 해제" }).click();
  await settled(page);
  expect((await api<{ parent: unknown }>(context, "GET", `/api/v1/issues/${alone.number}`)).parent).toBeNull();
  await page.close();
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
