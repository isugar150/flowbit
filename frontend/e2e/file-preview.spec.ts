import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { api, signIn, trackRequests } from "./support";

/**
 * A Word, Excel or PowerPoint file in a text opens in a preview over the page (D-187): drawn in the browser from the stored
 * file, without leaving the page. Any other file is only downloaded. The fixtures are tiny files made for this (Korean text,
 * a table, a sheet, two slides).
 */

type Issue = { key: string };
type Uploaded = { url: string; name: string; size: number };

const run = Date.now().toString(36);
const FIXTURES = path.join(__dirname, "fixtures");
const blank = { content: null, status: "BACKLOG", priority: "NONE", assigneeIds: [], startDate: null, dueDate: null, milestoneId: null, labelIds: [] };

let context: BrowserContext;
let issue: Issue;

/** Uploads a file the way the editor does, and returns the block the text keeps for it. */
async function fileBlock(name: string, mimeType: string, bytes?: Buffer) {
  await context.request.get("/api/v1/auth/csrf");
  const token = decodeURIComponent((await context.cookies()).find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "");
  const response = await context.request.post("/api/v1/editor-files", {
    headers: { "X-XSRF-TOKEN": token },
    multipart: { file: { name, mimeType, buffer: bytes ?? readFileSync(path.join(FIXTURES, name)) } },
  });
  expect(response.ok()).toBe(true);
  const stored = (await response.json()) as Uploaded;
  return { type: "fileBlock", attrs: { src: stored.url, name: stored.name, size: stored.size } };
}

/** The preview of a file: a dialog named after the file (the ticket itself is a dialog too). */
const previewOf = (page: Page, name: string) => page.getByRole("dialog", { name, exact: true });

test.beforeAll(async ({ browser, baseURL }) => {
  context = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await signIn(context);
  const project = await api<{ id: string }>(context, "POST", "/api/v1/projects", { name: `미리보기 ${run}`, description: "" });
  const blocks = [
    await fileBlock("sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    await fileBlock("sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    await fileBlock("sample.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    await fileBlock("memo.txt", "text/plain", Buffer.from("메모")),
  ];
  issue = await api<Issue>(context, "POST", "/api/v1/issues", {
    ...blank,
    title: "첨부 미리보기",
    projectId: project.id,
    content: { type: "doc", content: [...blocks, { type: "paragraph" }] },
  });
});

test.afterAll(async () => {
  await context.close();
});

test("an Office file opens in a preview over the page and closes with Esc", async ({}, testInfo) => {
  const page = await context.newPage();
  trackRequests(page);
  await page.goto(`/issue/${issue.key}`);

  // Word: the paragraphs, drawn in a text layer so that they can be selected and copied.
  const docx = previewOf(page, "sample.docx");
  await page.getByRole("link", { name: "sample.docx 미리보기" }).click();
  await expect(docx.getByText("2026년 4분기 업무 보고서")).toBeVisible({ timeout: 20_000 });
  await expect(docx.locator("canvas").first()).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("docx.png") });
  // The page behind has not moved: it is still the ticket.
  await expect(page).toHaveURL(new RegExp(`/issue/${issue.key}$`));
  await page.keyboard.press("Escape");
  await expect(docx).toHaveCount(0);

  // PowerPoint: the slides one below the other.
  const pptx = previewOf(page, "sample.pptx");
  await page.getByRole("link", { name: "sample.pptx 미리보기" }).click();
  await expect(pptx.getByText("Flowbit 첨부 미리보기")).toBeVisible({ timeout: 20_000 });
  await page.screenshot({ path: testInfo.outputPath("pptx.png") });
  await pptx.getByRole("button", { name: "닫기" }).click();
  await expect(pptx).toHaveCount(0);

  // Excel: the sheet with its tab.
  const xlsx = previewOf(page, "sample.xlsx");
  await page.getByRole("link", { name: "sample.xlsx 미리보기" }).click();
  await expect(xlsx.locator("canvas").first()).toBeVisible({ timeout: 20_000 });
  // The sheet is drawn once the file is read: its tab is there, "loading" is gone and no failure message took its place.
  await expect(xlsx.getByText("실적", { exact: true })).toBeVisible({ timeout: 20_000 });
  await expect(xlsx.getByText("파일을 불러오는 중…")).toHaveCount(0);
  await expect(xlsx.getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("xlsx.png") });
  // A click beside the window (on the dimmed page) closes it too.
  await page.mouse.click(10, 10);
  await expect(xlsx).toHaveCount(0);
});

test("the download button saves the file and a file that cannot be previewed is only downloaded", async () => {
  const page = await context.newPage();
  await page.goto(`/issue/${issue.key}`);

  const saved = page.waitForEvent("download");
  await page.getByRole("link", { name: "sample.docx 내려받기" }).click();
  expect((await saved).suggestedFilename()).toBe("sample.docx");
  await expect(previewOf(page, "sample.docx")).toHaveCount(0);

  // A text file has no preview: the whole block is the download.
  await expect(page.getByRole("link", { name: "memo.txt 미리보기" })).toHaveCount(0);
  const memo = page.waitForEvent("download");
  await page.getByRole("link", { name: "memo.txt 내려받기" }).click();
  expect((await memo).suggestedFilename()).toBe("memo.txt");
  await expect(previewOf(page, "memo.txt")).toHaveCount(0);
});
