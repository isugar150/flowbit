/**
 * Where one project's settings are reached: by the project's own address, or, from 관리자 설정 > 프로젝트, by the
 * administrator's (D-236). The two answer the same way (same bodies, same results), so the settings components serve both;
 * the administrator's works for any project in use, whether or not the signed-in administrator was added to it.
 */
export type ProjectApiScope = "project" | "admin";

/** `/api/v1/projects/{id}` for a project's own settings, `/api/v1/admin/projects/{id}` for the administrator's. */
export function projectPath(id: string, scope: ProjectApiScope = "project"): string {
  return `${scope === "admin" ? "/api/v1/admin/projects" : "/api/v1/projects"}/${encodeURIComponent(id)}`;
}
