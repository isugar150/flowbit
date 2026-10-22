import { readFileSync } from "node:fs";
import path from "node:path";
import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { api, signIn, trackRequests } from "./support";

/**
 * A Word, Excel, PowerPoint, PDF, text or CSV file in a text is shown in the text itself, the way a Figma or Google Sheets link
 * is, with a bar under it (name, a button that opens it large over the page, the download) (D-187). Any other file is only
 * downloaded. The fixtures are tiny files made for this (Korean text, a table, a sheet, two slides, two pages).
 */

type Issue = { key: string };
type Uploaded = { url: string; name: string; size: number };

const run = Date.now().toString(36);
const FIXTURES = path.join(__dirname, "fixtures");
const blank = { content: null, status: "BACKLOG", priority: "NONE", assigneeIds: [], startDate: null, dueDate: null, milestoneId: null, labelIds: [] };

let context: BrowserContext;
let office: Issue;
let others: Issue;

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

/** The block of a file in the text. */
const blockOf = (page: Page, name: string) => page.locator(`[data-file-block][data-name="${name}"]`);

/** The large preview of a file: a dialog named after the file (the ticket itself is a dialog too). */
const previewOf = (page: Page, name: string) => page.getByRole("dialog", { name, exact: true });

test.beforeAll(async ({ browser, baseURL }) => {
  context = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await signIn(context);
  const project = await api<{ id: string }>(context, "POST", "/api/v1/projects", { name: `미리보기 ${run}`, description: "" });
  const issueWith = (title: string, blocks: unknown[]) =>
    api<Issue>(context, "POST", "/api/v1/issues", { ...blank, title, projectId: project.id, content: { type: "doc", content: [...blocks, { type: "paragraph" }] } });
  office = await issueWith("Office 미리보기", [
    await fileBlock("sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    await fileBlock("sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    await fileBlock("sample.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    // A kind that has no preview.
    await fileBlock("회의록.hwp", "application/octet-stream", Buffer.from("hwp")),
  ]);
  others = await issueWith("PDF·텍스트·CSV 미리보기", [
    await fileBlock("sample.pdf", "application/pdf"),
    await fileBlock("sample.txt", "text/plain"),
    await fileBlock("sample.csv", "text/csv"),
    await fileBlock("legacy.csv", "text/csv"),
  ]);
});

test.afterAll(async () => {
  await context.close();
});

test("an Office file is shown in the text, and its preview button opens it large over the page", async ({}, testInfo) => {
  const page = await context.newPage();
  trackRequests(page);
  await page.goto(`/issue/${office.key}`);

  // Word: the paragraphs in the text itself, drawn in a text layer so that they can be selected and copied.
  const docxBlock = blockOf(page, "sample.docx");
  await docxBlock.scrollIntoViewIfNeeded();
  await expect(docxBlock.getByText("2026년 4분기 업무 보고서")).toBeVisible({ timeout: 20_000 });
  await expect(docxBlock.locator("canvas").first()).toBeVisible();
  // Under it, the bar: the name and size on the left, the preview and the download on the right.
  await expect(docxBlock.getByText("sample.docx", { exact: true })).toBeVisible();
  await expect(docxBlock.getByRole("link", { name: "sample.docx 내려받기" })).toBeVisible();
  // A drag in the document selects its text; it does not pick the block up (the grip beside the block moves it).
  await expect(docxBlock).not.toHaveAttribute("draggable", "true");
  await page.screenshot({ path: testInfo.outputPath("docx-inline.png") });

  // The preview button opens the same file large, over the ticket.
  const docx = previewOf(page, "sample.docx");
  await docxBlock.getByRole("button", { name: "sample.docx 미리보기" }).click();
  await expect(docx.getByText("2026년 4분기 업무 보고서")).toBeVisible({ timeout: 20_000 });
  await expect(docx.locator("canvas").first()).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("docx.png") });
  // The page behind has not moved: it is still the ticket.
  await expect(page).toHaveURL(new RegExp(`/issue/${office.key}$`));
  await page.keyboard.press("Escape");
  await expect(docx).toHaveCount(0);
  // The document in the text is still there.
  await expect(docxBlock.getByText("2026년 4분기 업무 보고서")).toBeVisible();

  // PowerPoint: the slides one below the other, in the text and large.
  const pptxBlock = blockOf(page, "sample.pptx");
  await pptxBlock.scrollIntoViewIfNeeded();
  await expect(pptxBlock.getByText("Flowbit 첨부 미리보기")).toBeVisible({ timeout: 20_000 });
  await page.screenshot({ path: testInfo.outputPath("pptx-inline.png") });
  const pptx = previewOf(page, "sample.pptx");
  await pptxBlock.getByRole("button", { name: "sample.pptx 미리보기" }).click();
  await expect(pptx.getByText("Flowbit 첨부 미리보기")).toBeVisible({ timeout: 20_000 });
  await page.screenshot({ path: testInfo.outputPath("pptx.png") });
  await pptx.getByRole("button", { name: "닫기" }).click();
  await expect(pptx).toHaveCount(0);

  // Excel: the sheet with its tab, in the text and large.
  const xlsxBlock = blockOf(page, "sample.xlsx");
  await xlsxBlock.scrollIntoViewIfNeeded();
  await expect(xlsxBlock.getByText("실적", { exact: true })).toBeVisible({ timeout: 20_000 });
  await expect(xlsxBlock.getByText("파일을 불러오는 중…")).toHaveCount(0);
  await expect(xlsxBlock.getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("xlsx-inline.png") });
  const xlsx = previewOf(page, "sample.xlsx");
  await xlsxBlock.getByRole("button", { name: "sample.xlsx 미리보기" }).click();
  await expect(xlsx.getByText("실적", { exact: true })).toBeVisible({ timeout: 20_000 });
  await expect(xlsx.getByText("파일을 불러오는 중…")).toHaveCount(0);
  await expect(xlsx.getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("xlsx.png") });
  // A click beside the window (on the dimmed page) closes it too.
  await page.mouse.click(10, 10);
  await expect(xlsx).toHaveCount(0);
});

test("a PDF, a text file and CSV files are shown in the text too, and open large from the preview button", async ({}, testInfo) => {
  const page = await context.newPage();
  trackRequests(page);
  await page.goto(`/issue/${others.key}`);

  // PDF: its pages with the text on them selectable, and which page is in view.
  const pdfBlock = blockOf(page, "sample.pdf");
  await pdfBlock.scrollIntoViewIfNeeded();
  await expect(pdfBlock.getByText("Flowbit PDF page one")).toBeVisible({ timeout: 20_000 });
  await expect(pdfBlock.locator("canvas").first()).toBeVisible();
  await expect(pdfBlock.getByText("1 / 2")).toBeVisible();
  await expect(pdfBlock.getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("pdf-inline.png") });
  const pdf = previewOf(page, "sample.pdf");
  await pdfBlock.getByRole("button", { name: "sample.pdf 미리보기" }).click();
  await expect(pdf.getByText("Flowbit PDF page one")).toBeVisible({ timeout: 20_000 });
  await page.screenshot({ path: testInfo.outputPath("pdf.png") });
  await page.keyboard.press("Escape");
  await expect(pdf).toHaveCount(0);

  // Text: as it is, in the text, as high as it is.
  const txtBlock = blockOf(page, "sample.txt");
  await txtBlock.scrollIntoViewIfNeeded();
  await expect(txtBlock.getByText("Flowbit 텍스트 미리보기")).toBeVisible({ timeout: 20_000 });
  await expect(txtBlock.getByText("둘째 줄")).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("txt-inline.png") });
  const txt = previewOf(page, "sample.txt");
  await txtBlock.getByRole("button", { name: "sample.txt 미리보기" }).click();
  await expect(txt.getByText("한글과 English 가 섞인 메모입니다.")).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("txt.png") });
  await page.keyboard.press("Escape");
  await expect(txt).toHaveCount(0);

  // CSV: a sheet, drawn by the Excel viewer, whether it is in UTF-8 or in the Korean legacy encoding.
  for (const name of ["sample.csv", "legacy.csv"]) {
    const block = blockOf(page, name);
    await block.scrollIntoViewIfNeeded();
    await expect(block.locator("canvas").first()).toBeVisible({ timeout: 20_000 });
    await expect(block.getByText("파일을 불러오는 중…")).toHaveCount(0);
    await expect(block.getByRole("alert")).toHaveCount(0);
    await page.screenshot({ path: testInfo.outputPath(`${name}-inline.png`) });
  }
  const csv = previewOf(page, "sample.csv");
  await blockOf(page, "sample.csv").getByRole("button", { name: "sample.csv 미리보기" }).click();
  await expect(csv.locator("canvas").first()).toBeVisible({ timeout: 20_000 });
  await expect(csv.getByText("파일을 불러오는 중…")).toHaveCount(0);
  await expect(csv.getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("csv.png") });
  await page.keyboard.press("Escape");
  await expect(csv).toHaveCount(0);
});

test("the download button saves the file and a file that cannot be previewed is only downloaded", async () => {
  const page = await context.newPage();
  await page.goto(`/issue/${office.key}`);

  const saved = page.waitForEvent("download");
  await page.getByRole("link", { name: "sample.docx 내려받기" }).click();
  expect((await saved).suggestedFilename()).toBe("sample.docx");
  await expect(previewOf(page, "sample.docx")).toHaveCount(0);

  // A file of a kind that has no preview has nothing to show and no preview button: the whole block is the download.
  await expect(page.getByRole("button", { name: "회의록.hwp 미리보기" })).toHaveCount(0);
  await expect(blockOf(page, "회의록.hwp").locator("canvas")).toHaveCount(0);
  const hwp = page.waitForEvent("download");
  await page.getByRole("link", { name: "회의록.hwp 내려받기" }).click();
  expect((await hwp).suggestedFilename()).toBe("회의록.hwp");
  await expect(previewOf(page, "회의록.hwp")).toHaveCount(0);
});
