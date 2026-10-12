import { cache } from "react";
import { cookies } from "next/headers";
import { fetchMe, fetchWorkspace, SESSION_COOKIE } from "@/lib/backend";
import type { Me } from "@/features/auth/types";
import type { Workspace } from "@/features/settings/types";

/**
 * The signed-in user for the current request, fetched once per render however many
 * server components ask. The backend decides; this only relays its answer.
 */
export const getCurrentUser = cache(async (): Promise<Me | null> => {
  const cookieHeader = await sessionCookieHeader();
  return cookieHeader ? fetchMe(cookieHeader) : null;
});

/** The workspace (name, logo) as the signed-in user sees it, fetched once per render. */
export const getWorkspace = cache(async (): Promise<Workspace | null> => {
  const cookieHeader = await sessionCookieHeader();
  return cookieHeader ? fetchWorkspace(cookieHeader) : null;
});

async function sessionCookieHeader(): Promise<string | null> {
  const session = (await cookies()).get(SESSION_COOKIE);
  return session ? `${SESSION_COOKIE}=${session.value}` : null;
}
