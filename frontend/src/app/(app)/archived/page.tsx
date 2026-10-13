import type { Metadata } from "next";
import { ArchiveIcon, LayoutGridIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { PillTabs } from "@/components/pill-tabs";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { ProjectTable } from "@/features/projects/components/project-table";
import type { ProjectSummary } from "@/features/projects/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

export const metadata: Metadata = { title: m.archivedTitle };

/**
 * The projects that were put away, greyed out. They cannot be entered; whoever manages one
 * restores it here. The other tab of the list of all projects (`/`).
 */
export default async function ArchivedProjectsPage() {
  const projects = (await serverGet<ProjectSummary[]>("/api/v1/projects?archived=true")) ?? [];

  return (
    <>
      <PageHeader crumbs={[{ label: m.title, icon: <LayoutGridIcon /> }]} />
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
                <ArchiveIcon />
              </EmptyMedia>
              <EmptyTitle>{m.archivedEmptyTitle}</EmptyTitle>
              <EmptyDescription>{m.archivedEmptyDescription}</EmptyDescription>
            </EmptyHeader>
          </Empty>
        </div>
      ) : (
        <>
          <p className="border-y bg-muted/50 px-5 py-2 text-[0.8125rem] leading-5 text-muted-foreground">{m.archivedNotice}</p>
          <ProjectTable projects={projects} archived />
        </>
      )}
    </>
  );
}
