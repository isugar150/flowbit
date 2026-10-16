import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { api, frames, pick, recordFrames, settled, signIn, slowDown, trackRequests, watchForReloads } from "./support";

/**
 * What a person sees while a change is saved. A change shows at once and stays: the value never
 * goes back to the old one on the way, controls do not grey out and come back, and the document
 * is never loaded again. Each test records every painted frame of the part it changes
 * (recordFrames) with the network slowed down, so a state that shows for a moment is caught.
 */

type Issue = { id: string; number: number; key: string; title: string };
type Project = { id: string; name: string };

let context: BrowserContext;
let project: Project;
let member: { id: string; name: string };

const paragraph = (text: string) => ({ type: "doc", content: [{ type: "paragraph", content: [{ type: "text", text }] }] });

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

async function newPage(): Promise<Page> {
  const page = await context.newPage();
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
  const run = Date.now().toString(36);
  project = await api<Project>(context, "POST", "/api/v1/projects", { name: `변경 ${run}`, description: "" });
  const created = await api<{ id: string }>(context, "POST", "/api/v1/admin/members", {
    loginId: `e2e-${run}`,
    password: "e2e-password-5678",
    displayName: `동료 ${run}`,
    role: "MEMBER",
  });
  member = { id: created.id, name: `동료 ${run}` };
  await api(context, "PUT", `/api/v1/projects/${project.id}/members/${member.id}`, { role: "EDITOR" });
});

test.afterAll(async () => {
  await context.close();
});

test("issue dialog: a picked status shows at once and stays", async () => {
  const issue = await newIssue("대화상자에서 상태 바꾸기");
  const page = await newPage();
  await openIssue(page, issue);
  const noReloads = watchForReloads(page);
  await slowDown(page);
  await recordFrames(page, "status", () => {
    const chip = document.querySelector<HTMLButtonElement>('[role=dialog] button[aria-label^="상태:"]');
    return chip ? `${chip.getAttribute("aria-label")}|${chip.disabled ? "disabled" : "enabled"}` : "missing";
  });

  await pick(page, page.getByRole("dialog").getByRole("button", { name: /^상태:/ }), "진행 중");
  await settled(page);

  expect(await frames(page, "status")).toEqual(["상태: 백로그|enabled", "상태: 진행 중|enabled"]);
  noReloads();
  // The server has it too.
  expect((await api<{ status: string }>(context, "GET", `/api/v1/issues/${issue.number}`)).status).toBe("IN_PROGRESS");
  await page.close();
});

test("issue dialog: every property keeps its new value through the save", async () => {
  const issue = await newIssue("대화상자에서 여러 속성 바꾸기");
  const page = await newPage();
  await openIssue(page, issue);
  await slowDown(page);
  const dialog = page.getByRole("dialog");
  await recordFrames(page, "values", () => {
    const label = (prefix: string) =>
      document.querySelector(`[role=dialog] button[aria-label^="${prefix}:"]`)?.getAttribute("aria-label") ?? "missing";
    return [label("우선순위"), label("담당자")].join(" / ");
  });
  const before = (await frames(page, "values"))[0];

  await pick(page, dialog.getByRole("button", { name: /^우선순위:/ }), "긴급");
  await settled(page);
  // Assignees are ticked in a list that stays open for more (D-125); Escape closes it.
  await dialog.getByRole("button", { name: /^담당자:/ }).click();
  await page.getByRole("option").filter({ hasText: member.name }).click();
  await page.keyboard.press("Escape");
  await settled(page);

  expect(await frames(page, "values")).toEqual([
    before,
    before.replace(/우선순위: [^/]+ \//, "우선순위: 긴급 /"),
    `우선순위: 긴급 / 담당자: ${member.name}`,
  ]);
  await page.close();
});

test("issue dialog: two quick changes reach the server one after the other", async () => {
  const issue = await newIssue("빠르게 두 번 바꾸기");
  const page = await newPage();
  await openIssue(page, issue);
  const order: string[] = [];
  // The first change is the slow one: sent side by side, the second would overtake it.
  await page.route(`**/api/v1/issues/${issue.number}`, async (route) => {
    if (route.request().method() !== "PATCH") return route.fallback();
    const field = Object.keys(route.request().postDataJSON() as object)[0];
    order.push(`start ${field}`);
    await new Promise((resolve) => setTimeout(resolve, field === "status" ? 700 : 50));
    const response = await route.fetch();
    order.push(`end ${field}`);
    await route.fulfill({ response });
  });
  const dialog = page.getByRole("dialog");

  await pick(page, dialog.getByRole("button", { name: /^상태:/ }), "완료");
  await pick(page, dialog.getByRole("button", { name: /^우선순위:/ }), "높음");
  await expect(dialog.getByRole("button", { name: "상태: 완료" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "우선순위: 높음" })).toBeVisible();
  await settled(page);

  expect(order).toEqual(["start status", "end status", "start priority", "end priority"]);
  const saved = await api<{ status: string; priority: string }>(context, "GET", `/api/v1/issues/${issue.number}`);
  expect(saved).toMatchObject({ status: "DONE", priority: "HIGH" });
  await expect(dialog.getByRole("button", { name: "상태: 완료" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "우선순위: 높음" })).toBeVisible();
  await page.close();
});

test("issue dialog: a change the server refuses goes back, with the reason", async () => {
  const issue = await newIssue("거절된 변경");
  const page = await newPage();
  await openIssue(page, issue);
  await page.route(`**/api/v1/issues/${issue.number}`, async (route) => {
    if (route.request().method() !== "PATCH") return route.fallback();
    await new Promise((resolve) => setTimeout(resolve, 300));
    await route.fulfill({
      status: 409,
      contentType: "application/problem+json",
      body: JSON.stringify({ code: "E2E_REFUSED", title: "지금은 바꿀 수 없습니다." }),
    });
  });
  await recordFrames(page, "status", () =>
    document.querySelector('[role=dialog] button[aria-label^="상태:"]')?.getAttribute("aria-label") ?? "missing",
  );

  await pick(page, page.getByRole("dialog").getByRole("button", { name: /^상태:/ }), "진행 중");
  await expect(page.getByRole("dialog").getByRole("alert")).toBeVisible();
  await settled(page);

  expect(await frames(page, "status")).toEqual(["상태: 백로그", "상태: 진행 중", "상태: 백로그"]);
  await page.close();
});

test("issue list: a status picked on a line moves that line at once and greys nothing out", async () => {
  const issue = await newIssue("목록에서 상태 바꾸기");
  await newIssue("옆에 있는 다른 티켓");
  const page = await newPage();
  await page.goto(`/project/${project.id}/issues`);
  const row = page.locator(`li[data-presence-id="issue-row:${issue.number}"]`);
  await expect(row).toBeVisible();
  const noReloads = watchForReloads(page);
  await slowDown(page);
  await recordFrames(
    page,
    "row",
    (number) => {
      const line = document.querySelector(`li[data-presence-id="issue-row:${number}"]`);
      const group = line?.closest("section")?.getAttribute("aria-label") ?? "missing";
      const disabled = document.querySelectorAll('li[data-presence-id^="issue-row:"] button:disabled').length;
      return `${group}|disabled ${disabled}`;
    },
    issue.number,
  );

  await pick(page, row.getByRole("button", { name: /상태 바꾸기/ }), "진행 중");
  await settled(page);

  expect(await frames(page, "row")).toEqual(["백로그|disabled 0", "진행 중|disabled 0"]);
  noReloads();
  await page.close();
});

test("issue dialog: a reaction is there the moment it is pressed", async () => {
  const issue = await newIssue("반응 달기");
  const page = await newPage();
  await openIssue(page, issue);
  await slowDown(page);
  await recordFrames(page, "reactions", () =>
    [...document.querySelectorAll<HTMLButtonElement>('[data-presence-id="reactions"] button[aria-pressed]')]
      .map((button) => `${button.textContent}:${button.getAttribute("aria-pressed")}:${button.disabled ? "disabled" : "enabled"}`)
      .join(",") || "none",
  );
  const reactions = page.locator('[data-presence-id="reactions"]');

  await reactions.getByRole("button", { name: "반응 추가" }).or(reactions.locator("button").last()).first().click();
  await page.getByRole("button", { name: "👍", exact: true }).click();
  await settled(page);
  await reactions.locator("button[aria-pressed]").click();
  await settled(page);

  expect(await frames(page, "reactions")).toEqual(["none", "👍1:true:enabled", "none"]);
  await page.close();
});

test("issue dialog: following shows at once", async () => {
  const issue = await newIssue("구독");
  const page = await newPage();
  await openIssue(page, issue);
  await slowDown(page);
  // The author follows their own issue from the start.
  const button = page.locator('[data-presence-id="activity"]').getByRole("button", { name: /^구독/ });
  await expect(button).toHaveText("구독 취소");
  await recordFrames(page, "follow", () => {
    const found = [...document.querySelectorAll<HTMLButtonElement>('[data-presence-id="activity"] button')].find((item) =>
      item.textContent?.startsWith("구독"),
    );
    return found ? `${found.textContent}|${found.disabled ? "disabled" : "enabled"}` : "missing";
  });

  await button.click();
  await settled(page);

  expect(await frames(page, "follow")).toEqual(["구독 취소|enabled", "구독|enabled"]);
  await page.close();
});

test("issue dialog: a sent comment is never missing from both the box and the list", async () => {
  const issue = await newIssue("댓글 쓰기");
  const page = await newPage();
  await openIssue(page, issue);
  await slowDown(page);
  const composer = page.locator('[data-presence-id="comment"]');
  await composer.locator(".ProseMirror").click();
  await page.keyboard.type("확인했습니다");
  await recordFrames(page, "comment", () => {
    const written = document.querySelector('[data-presence-id="comment"] .ProseMirror')?.textContent ?? "";
    const listed = [...document.querySelectorAll('[data-presence-id="activity"] ol > li')].filter((item) =>
      item.textContent?.includes("확인했습니다"),
    ).length;
    return `box "${written}" list ${listed}`;
  });

  await composer.getByRole("button", { name: /댓글/ }).click();
  await settled(page);

  expect(await frames(page, "comment")).toEqual(['box "확인했습니다" list 0', 'box "" list 1']);
  await page.close();
});

test("issue dialog: a new sub-issue is never missing from both the field and the list", async () => {
  const issue = await newIssue("하위 티켓 만들기");
  const page = await newPage();
  await openIssue(page, issue);
  await slowDown(page);
  const section = page.locator('[data-presence-id="sub-issues"]');
  await section.getByRole("button").first().click();
  await section.locator("input").fill("하위 작업 하나");
  await recordFrames(page, "sub", () => {
    const typed = document.querySelector<HTMLInputElement>('[data-presence-id="sub-issues"] input')?.value ?? "";
    const listed = document.querySelectorAll('[data-presence-id="sub-issues"] ul > li').length;
    return `field "${typed}" list ${listed}`;
  });

  await section.locator("input").press("Enter");
  await settled(page);

  expect(await frames(page, "sub")).toEqual(['field "하위 작업 하나" list 0', 'field "" list 1']);
  await page.close();
});

test("issue dialog: the body is there in the first frame the issue is", async () => {
  const issue = await newIssue("본문이 있는 티켓", { content: paragraph("본문 첫 줄입니다") });
  const page = await newPage();
  await page.goto(`/project/${project.id}/issues`);
  await expect(page.locator(`li[data-presence-id="issue-row:${issue.number}"]`)).toBeVisible();
  await recordFrames(page, "body", () => {
    const body = document.querySelector('[role=dialog] [data-presence-id="body"]');
    return body ? (body.textContent?.includes("본문 첫 줄입니다") ? "written" : "blank") : "not open";
  });

  await page.locator(`li[data-presence-id="issue-row:${issue.number}"] a`).click();
  await expect(page.getByRole("dialog").getByText("본문 첫 줄입니다")).toBeVisible();
  await settled(page);

  expect(await frames(page, "body")).toEqual(["not open", "written"]);
  await page.close();
});

test("project overview: status and following show at once", async () => {
  const page = await newPage();
  await page.goto(`/project/${project.id}`);
  const status = page.getByRole("button", { name: /^상태:/ });
  await expect(status).toBeVisible();
  const noReloads = watchForReloads(page);
  await slowDown(page);
  await recordFrames(
    page,
    "overview",
    () => {
      const chip = document.querySelector<HTMLButtonElement>('button[aria-label^="상태:"]');
      const follow = [...document.querySelectorAll<HTMLButtonElement>("button")].find((item) => item.textContent?.startsWith("구독"));
      const state = (element: { disabled: boolean } | null | undefined) => (element?.disabled ? "disabled" : "enabled");
      return [
        `${chip?.getAttribute("aria-label")} ${state(chip)}`,
        `${follow?.textContent} ${state(follow)}`,
      ].join(" / ");
    },
  );

  await pick(page, status, "진행 중");
  await settled(page);
  await page.getByRole("button", { name: "구독", exact: true }).click();
  await settled(page);

  // Roles are changed in the project settings, not on the overview.
  expect(await frames(page, "overview")).toEqual([
    "상태: 백로그 enabled / 구독 enabled",
    "상태: 진행 중 enabled / 구독 enabled",
    "상태: 진행 중 enabled / 구독 취소 enabled",
  ]);
  noReloads();
  await page.close();
});

test("calendar: an issue dragged to another day is on that day at once", async () => {
  const now = new Date();
  const day = (date: number) => `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(date).padStart(2, "0")}`;
  const issue = await newIssue("캘린더에서 옮기기", { dueDate: day(15) });
  const page = await newPage();
  await page.goto(`/project/${project.id}/issues?view=calendar`);
  const bar = page.locator("td a").filter({ hasText: issue.title });
  await expect(bar).toBeVisible();
  const noReloads = watchForReloads(page);
  await slowDown(page);
  await recordFrames(
    page,
    "day",
    (title) => {
      const cells = [...document.querySelectorAll("td")].filter((cell) =>
        [...cell.querySelectorAll("a")].some((link) => link.textContent === title),
      );
      return cells.map((cell) => cell.querySelector("div")?.textContent).join(",") || "nowhere";
    },
    issue.title,
  );

  await bar.dragTo(page.locator("td").filter({ has: page.locator("div", { hasText: /^16$/ }) }).first());
  await settled(page);

  expect(await frames(page, "day")).toEqual(["15", "16"]);
  noReloads();
  expect((await api<{ dueDate: string }>(context, "GET", `/api/v1/issues/${issue.number}`)).dueDate).toBe(day(16));
  await page.close();
});

test("milestone: a date picked in the opened milestone shows at once", async () => {
  await api(context, "POST", `/api/v1/projects/${project.id}/milestones`, { name: "첫 목표", targetDate: null });
  const page = await newPage();
  await page.goto(`/project/${project.id}`);
  await page.getByRole("link", { name: "첫 목표 열기" }).click();
  const chip = page.getByRole("dialog").getByRole("button", { name: /^목표일:/ });
  await expect(chip).toBeVisible();
  await settled(page);
  await slowDown(page);
  await recordFrames(page, "date", () => {
    const found = document.querySelector<HTMLButtonElement>('[role=dialog] button[aria-label^="목표일:"]');
    return `${found?.getAttribute("aria-label")?.replace(/\d{4}-\d{2}-/, "")}|${found?.disabled ? "disabled" : "enabled"}`;
  });

  await chip.click();
  await page.locator("td button").filter({ hasText: /^20$/ }).first().click();
  await settled(page);

  expect(await frames(page, "date")).toEqual(["목표일: 목표일|enabled", "목표일: 20|enabled"]);
  await page.close();
});

test("administrator's members: a role and a project permission show at once", async () => {
  const page = await newPage();
  await page.goto("/admin/members");
  await page.getByRole("button", { name: new RegExp(member.name) }).click();
  const role = page.getByRole("combobox", { name: `${member.name}의 역할` });
  const permission = page.getByRole("combobox", { name: `${project.name}에서의 권한` });
  await expect(permission).toBeVisible();
  const before = await permission.inputValue();
  const next = before === "MANAGER" ? "EDITOR" : "MANAGER";
  await slowDown(page);
  await recordFrames(
    page,
    "member",
    ({ person, projectName }) => {
      const state = (label: string) => {
        const select = document.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`);
        return `${select?.value} ${select?.disabled ? "disabled" : "enabled"}`;
      };
      return `${state(`${person}의 역할`)} / ${state(`${projectName}에서의 권한`)}`;
    },
    { person: member.name, projectName: project.name },
  );

  await role.selectOption("ADMIN");
  await settled(page);
  await permission.selectOption(next);
  await settled(page);

  expect(await frames(page, "member")).toEqual([
    `MEMBER enabled / ${before} enabled`,
    `ADMIN enabled / ${before} enabled`,
    `ADMIN enabled / ${next} enabled`,
  ]);
  await page.close();
});

test("project settings: a member's role shows at once", async () => {
  const page = await newPage();
  await page.goto(`/project/${project.id}/settings`);
  const role = page.getByRole("combobox", { name: `${member.name}의 역할` });
  await expect(role).toBeVisible();
  const before = await role.inputValue();
  const next = before === "EDITOR" ? "VIEWER" : "EDITOR";
  await slowDown(page);
  await recordFrames(
    page,
    "role",
    (name) => {
      const select = document.querySelector<HTMLSelectElement>(`select[aria-label="${name}의 역할"]`);
      return `${select?.value} ${select?.disabled ? "disabled" : "enabled"}`;
    },
    member.name,
  );

  await role.selectOption(next);
  await settled(page);

  expect(await frames(page, "role")).toEqual([`${before} enabled`, `${next} enabled`]);
  const members = await api<{ userId: string; role: string }[]>(context, "GET", `/api/v1/projects/${project.id}/members`);
  expect(members.find((item) => item.userId === member.id)?.role).toBe(next);
  await page.close();
});
