import { describe, expect, it } from "vitest";
import { projectPath } from "./scope";

describe("projectPath", () => {
  const id = "0199e5a2-7c3f-7a10-8a4e-3d2f1b6c9e01";

  it("is the project's own address unless the administrator's is asked for", () => {
    expect(projectPath(id)).toBe(`/api/v1/projects/${id}`);
    expect(projectPath(id, "project")).toBe(`/api/v1/projects/${id}`);
  });

  it("is under /api/v1/admin for the administrator's scope", () => {
    expect(projectPath(id, "admin")).toBe(`/api/v1/admin/projects/${id}`);
  });
});
