import { createHmac } from "node:crypto";
import { expect, test, type BrowserContext } from "@playwright/test";
import { api, signIn } from "./support";

/**
 * Git integration (docs/features/git-integration.md "인수 기준", last line): adding a connection
 * shows its webhook address and secret, and an issue a pull request names shows that pull request
 * in its side panel. The Git server is played by signed calls to the webhook address.
 */

type Issue = { id: string; number: number; key: string; title: string };
type Connection = { id: string; name: string; webhookUrl: string };

const run = Date.now().toString(36);
let context: BrowserContext;

test.beforeAll(async ({ browser, baseURL }) => {
  context = await browser.newContext({ baseURL, locale: "ko-KR", viewport: { width: 1440, height: 900 } });
  await signIn(context);
});

test.afterAll(async () => {
  await context.close();
});

test("연결을 추가하면 주소와 비밀 값이 보이고, PR 이 걸린 티켓의 옆 패널에 PR 줄과 브랜치 이름 복사가 보인다", async () => {
  const page = await context.newPage();
  await page.goto("/admin/git");
  await page.getByRole("button", { name: "연결 추가" }).click();
  // GitHub comes first in the list (D-149); this test plays a Gitea server.
  await page.getByLabel("종류").selectOption("GITEA");
  await page.getByLabel("이름").fill(`Gitea ${run}`);
  await page.getByLabel("서버 주소").fill("https://git.example.com");
  await page.getByRole("button", { name: "저장", exact: true }).click();

  // The new connection opens with what goes into the Git server; the secret only this once.
  await expect(page.getByText("지금만 보입니다.", { exact: false })).toBeVisible();
  const secretField = page.getByLabel("비밀 값");
  await expect(secretField).toHaveValue(/^[0-9a-f]{64}$/);
  const secret = await secretField.inputValue();
  await expect(page.getByLabel("웹훅 주소")).toHaveValue(/\/api\/v1\/integrations\/git\/[0-9a-f-]{36}\/webhook$/);

  const connection = (await api<Connection[]>(context, "GET", "/api/v1/admin/git/connections")).find((it) => it.name === `Gitea ${run}`);
  expect(connection).toBeDefined();

  // A pull request names a new issue: Gitea calls the webhook.
  const issue = await api<Issue>(context, "POST", "/api/v1/issues", { title: `Git 연동 ${run}` });
  const body = JSON.stringify({
    action: "opened",
    number: 34,
    pull_request: {
      number: 34,
      title: `${issue.key} 로그인 오류 수정`,
      body: "",
      state: "open",
      draft: false,
      merged: false,
      html_url: "https://git.example.com/acme/web/pulls/34",
      user: { login: "e2e-admin", email: "" },
      head: { ref: `e2e-admin/${issue.key.toLowerCase()}-login` },
    },
    repository: { full_name: "acme/web", html_url: "https://git.example.com/acme/web", default_branch: "main" },
    sender: { login: "e2e-admin" },
  });
  const delivery = await context.request.post(`/api/v1/integrations/git/${connection?.id}/webhook`, {
    headers: {
      "Content-Type": "application/json",
      "X-Gitea-Event": "pull_request",
      "X-Gitea-Signature": createHmac("sha256", secret).update(body).digest("hex"),
    },
    data: body,
  });
  expect(delivery.status()).toBe(200);
  expect((await delivery.json()).linkedIssues).toEqual([issue.key]);

  await page.goto(`/issue/${issue.key}`);
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "Git", exact: true })).toBeVisible();
  await expect(dialog.getByRole("link", { name: /web #34 .*로그인 오류 수정/ })).toBeVisible();
  // The work started: the issue moved on.
  await expect(dialog.getByText("진행 중").first()).toBeVisible();

  // Git is in use, so the key opens a menu that copies a branch name.
  await dialog.getByRole("button", { name: `${issue.key} 메뉴` }).click();
  await expect(page.getByRole("menuitem", { name: /브랜치 이름 복사/ })).toContainText(`e2e-admin/${issue.key.toLowerCase()}`);
});
