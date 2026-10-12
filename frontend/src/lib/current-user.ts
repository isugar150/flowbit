import { cache } from "react";
import { cookies } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { fetchJson, fetchMe, fetchWorkspace, SESSION_COOKIE } from "@/lib/backend";
import type { Me } from "@/features/auth/types";
import type { Project, ProjectEntry, ProjectSummary } from "@/features/projects/types";
import type { Workspace } from "@/features/settings/types";

/** Where a request with a session cookie that no longer works is sent: it clears the cookie, then goes to sign-in. */
const SESSION_EXPIRED = "/session-expired";

/**
 * The signed-in user for the current request, fetched once per render however many
 * server components ask. The backend decides; this only relays its answer.
 */
export const getCurrentUser = cache(async (): Promise<Me | null> => {
  const cookieHeader = await sessionCookieHeader();
  return cookieHeader ? fetchMe(cookieHeader) : null;
});

/**
 * The signed-in user, for pages that only make sense signed in. proxy.ts lets a request through
 * when it carries a session cookie without asking the backend, so this is where a cookie that
 * no longer works is found out.
 */
export async function requireUser(): Promise<Me> {
  return (await getCurrentUser()) ?? redirect(SESSION_EXPIRED);
}

/** The workspace (name, logo) as the signed-in user sees it, fetched once per render. */
export const getWorkspace = cache(async (): Promise<Workspace> => {
  const cookieHeader = await sessionCookieHeader();
  const workspace = cookieHeader ? await fetchWorkspace(cookieHeader) : null;
  return workspace ?? redirect(SESSION_EXPIRED);
});

/**
 * Every project, fetched once per render for the sidebar and the home page. `myRole` is null for
 * the ones the signed-in user may not enter.
 */
export const getProjects = cache(
  async (): Promise<ProjectSummary[]> => (await serverGet<ProjectSummary[]>("/api/v1/projects")) ?? [],
);

/**
 * Reads one backend resource as the signed-in user from a server component.
 * Null means signed out, not allowed, or not found.
 */
export async function serverGet<T>(path: string): Promise<T | null> {
  const cookieHeader = await sessionCookieHeader();
  return cookieHeader ? fetchJson<T>(path, cookieHeader) : null;
}

async function sessionCookieHeader(): Promise<string | null> {
  const session = (await cookies()).get(SESSION_COOKIE);
  return session ? `${SESSION_COOKIE}=${session.value}` : null;
}

/**
 * One project for the project pages, fetched once per render. Someone who sees the project in
 * the list but has not been added to it is sent to /no-access/{id}, which says whom to ask; a
 * project that does not exist is "not found".
 */
export const loadProject = cache(async (id: string): Promise<Project> => {
  const path = `/api/v1/projects/${encodeURIComponent(id)}`;
  const project = await serverGet<Project>(path);
  if (project) return project;
  const entry = await serverGet<ProjectEntry>(`${path}/access`);
  if (entry && !entry.myRole) redirect(`/no-access/${entry.id}`);
  notFound();
});
