import { defineConfig } from "@playwright/test";

/**
 * End-to-end tests (docs/04-frontend-guide.md "테스트와 검사"). They need a backend on a database of
 * its own and a production build of this app pointed at it; they never start servers themselves,
 * so a development database is not touched by accident.
 */
export default defineConfig({
  testDir: "./e2e",
  // The tests share one workspace and change the same issues.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3100",
    // The system browser: the one Playwright downloads may not match the installed package.
    channel: process.env.E2E_BROWSER_CHANNEL ?? "msedge",
    viewport: { width: 1440, height: 900 },
    locale: "ko-KR",
  },
});
