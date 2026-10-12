import { NextResponse, type NextRequest } from "next/server";
import { BackendUnavailableError, fetchSetupCompleted, SESSION_COOKIE } from "@/lib/backend";
import { safeNextPath } from "@/lib/safe-next-path";

/**
 * Setup is finished exactly once and never undone, so once the backend has said "completed"
 * it is not asked again. (After wiping a development database, restart this server.)
 */
let setupCompleted = false;

async function isSetupCompleted(): Promise<boolean> {
  if (!setupCompleted) setupCompleted = await fetchSetupCompleted();
  return setupCompleted;
}

/**
 * Sends each page request where it belongs (docs/features/M1-auth-and-setup.md, "리다이렉트 규칙").
 *
 * This runs before every page, so it must be cheap: it looks only at whether a session cookie
 * is present and never asks the backend who the user is. A cookie that turns out to be invalid
 * is caught when the page loads its data (`requireUser`), which goes to `/session-expired`.
 * None of this is a security boundary; the backend authorizes every API call on its own.
 */
export async function proxy(request: NextRequest) {
  const { pathname, search, searchParams } = request.nextUrl;
  const redirectTo = (path: string) => NextResponse.redirect(new URL(path, request.url));

  if (pathname === "/session-expired" || pathname === "/unavailable") return NextResponse.next();

  try {
    if (!(await isSetupCompleted())) {
      return pathname === "/setup" ? NextResponse.next() : redirectTo("/setup");
    }
  } catch (error) {
    if (!(error instanceof BackendUnavailableError)) throw error;
    return NextResponse.rewrite(new URL("/unavailable", request.url));
  }

  const hasSession = request.cookies.has(SESSION_COOKIE);
  if (pathname === "/setup") return redirectTo(hasSession ? "/" : "/login");
  if (pathname === "/login") {
    return hasSession ? redirectTo(safeNextPath(searchParams.get("next"))) : NextResponse.next();
  }
  if (!hasSession) {
    const next = pathname + search;
    return redirectTo(next === "/" ? "/login" : `/login?next=${encodeURIComponent(next)}`);
  }
  return NextResponse.next();
}

export const config = {
  // Pages only: API calls, Next.js internals and files with an extension pass straight through.
  matcher: ["/((?!api/|_next/|.*\\..*).*)"],
};
