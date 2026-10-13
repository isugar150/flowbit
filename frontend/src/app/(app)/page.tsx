import type { Metadata } from "next";
import { BoxIcon, LayoutGridIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { PillTabs } from "@/components/pill-tabs";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { CreateProjectDialog } from "@/features/projects/components/create-project-dialog";
import { ProjectTable } from "@/features/projects/components/project-table";
import type { ProjectSummary } from "@/features/projects/types";
import { getProjects } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

export const metadata: Metadata = { title: m.title };

/**
 * All projects as a table (features/projects/components/project-table.tsx). The second tab,
 * "아카이브됨", lists the projects that were put away (`/archived`).
 */
export default async function HomePage() {
  // The same list the sidebar loaded for this request; no second call to the backend.
  const projects: ProjectSummary[] = await getProjects();

  return (
    <>
      <PageHeader
        crumbs={[{ label: m.title, icon: <LayoutGridIcon /> }]}
        actions={projects.length > 0 ? <CreateProjectDialog variant="ghost" /> : null}
      />
      {/* Shown with no projects too: everything may have been archived. */}
      <PillTabs
        label={m.listTabsLabel}
        tabs={[
          { href: "/", label: m.all },
          { href: "/archived", label: m.archivedTab },
        ]}
      />
      {projects.length === 0 ? (
        <div className="flex flex-1 items-center justify-center p-6">
          <Empty>
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <BoxIcon />
              </EmptyMedia>
              <EmptyTitle>{m.emptyTitle}</EmptyTitle>
              <EmptyDescription>{m.emptyDescription}</EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
              <CreateProjectDialog variant="button" />
            </EmptyContent>
          </Empty>
        </div>
      ) : (
        <ProjectTable projects={projects} />
      )}
    </>
  );
}
