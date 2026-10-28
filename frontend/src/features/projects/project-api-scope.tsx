"use client";

import { createContext, useContext, useMemo } from "react";
import {
  approveProjectAccess,
  archiveProject,
  putProjectMember,
  rejectProjectAccess,
  removeProjectIcon,
  removeProjectMember,
  updateProject,
  uploadProjectIcon,
} from "./api";
import type { ProjectApiScope } from "./scope";

const ProjectApiScopeContext = createContext<ProjectApiScope>("project");

/**
 * Tells the settings components under it which address to reach the project by (D-236): the project's own, as on
 * /project/[id]/settings, or the administrator's, as on 관리자 설정 > 프로젝트. Without one they use the project's own.
 */
export function ProjectApiScopeProvider({ scope, children }: { scope: ProjectApiScope; children: React.ReactNode }) {
  return <ProjectApiScopeContext.Provider value={scope}>{children}</ProjectApiScopeContext.Provider>;
}

export function useProjectApiScope(): ProjectApiScope {
  return useContext(ProjectApiScopeContext);
}

/** The calls a project's settings make, reaching the project the way the nearest [ProjectApiScopeProvider] says. */
export function useProjectApi() {
  const scope = useProjectApiScope();
  return useMemo(
    () => ({
      scope,
      updateProject: (input: Omit<Parameters<typeof updateProject>[0], "scope">) => updateProject({ ...input, scope }),
      archiveProject: (id: string) => archiveProject(id, scope),
      uploadProjectIcon: (id: string, file: File) => uploadProjectIcon(id, file, scope),
      removeProjectIcon: (id: string) => removeProjectIcon(id, scope),
      putProjectMember: (input: Omit<Parameters<typeof putProjectMember>[0], "scope">) => putProjectMember({ ...input, scope }),
      removeProjectMember: (input: Omit<Parameters<typeof removeProjectMember>[0], "scope">) => removeProjectMember({ ...input, scope }),
      approveProjectAccess: (input: Omit<Parameters<typeof approveProjectAccess>[0], "scope">) => approveProjectAccess({ ...input, scope }),
      rejectProjectAccess: (input: Omit<Parameters<typeof rejectProjectAccess>[0], "scope">) => rejectProjectAccess({ ...input, scope }),
    }),
    [scope],
  );
}
