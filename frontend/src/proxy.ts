import { NextResponse, type NextRequest } from "next/server";
import { BackendUnavailableError, fetchMe, fetchSetupCompleted, SESSION_COOKIE } from "@/lib/backend";
import { safeNextPath } from "@/lib/safe-next-path";

/**
 * Sends each page request where it belongs (docs/features/M1-auth-and-setup.md, "리다이렉트 규칙").
 * This is a convenience for the person using the app, not a security boundary:
 * the backend authorizes every API call on its own.
 */
export async function proxy(request: NextRequest) {
  const { pathname, search, searchParams } = request.nextUrl;
  const redirectTo = (path: string) => NextResponse.redirect(new URL(path, request.url));

  try {
    if (!(await fetchSetupCompleted())) {
      return pathname === "/setup" ? NextResponse.next() : redirectTo("/setup");
    }

    const session = request.cookies.get(SESSION_COOKIE);
    const me = session ? await fetchMe(`${SESSION_COOKIE}=${session.value}`) : null;

    if (pathname === "/unavailable") return redirectTo("/");
    if (pathname === "/setup") return redirectTo(me ? "/" : "/login");
    if (pathname === "/login") {
      return me ? redirectTo(safeNextPath(searchParams.get("next"))) : NextResponse.next();
    }
    if (!me) {
      const next = pathname + search;
      return redirectTo(next === "/" ? "/login" : `/login?next=${encodeURIComponent(next)}`);
    }
    return NextResponse.next();
  } catch (error) {
    if (!(error instanceof BackendUnavailableError)) throw error;
    return pathname === "/unavailable"
      ? NextResponse.next()
      : NextResponse.rewrite(new URL("/unavailable", request.url));
  }
}

export const config = {
  // Pages only: API calls, Next.js internals and files with an extension pass straight through.
  matcher: ["/((?!api/|_next/|.*\\..*).*)"],
};
