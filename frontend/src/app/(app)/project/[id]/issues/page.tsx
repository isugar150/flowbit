import { redirect } from "next/navigation";

/**
 * A project has no ticket list of its own any more (D-135): its tickets are the list of all
 * tickets filtered to the project. Old links land there with their filters.
 */
export default async function ProjectIssuesPage({ params, searchParams }: PageProps<"/project/[id]/issues">) {
  const [{ id }, query] = await Promise.all([params, searchParams]);
  const next = new URLSearchParams({ project: id });
  for (const [key, value] of Object.entries(query)) {
    if (key === "project") continue;
    for (const one of Array.isArray(value) ? value : value === undefined ? [] : [value]) next.append(key, one);
  }
  redirect(`/issues?${next.toString()}`);
}
