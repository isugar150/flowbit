import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/backend";

/**
 * Reached when a page found that the session cookie no longer works (signed out elsewhere,
 * expired, account disabled). Server components cannot remove cookies, so this handler does,
 * then sends the person to sign in. Removing it matters: proxy.ts treats "has a cookie" as
 * "signed in" and would otherwise bounce them straight back.
 */
export function GET(request: NextRequest) {
  const response = NextResponse.redirect(new URL("/login", request.url));
  response.cookies.delete(SESSION_COOKIE);
  return response;
}
