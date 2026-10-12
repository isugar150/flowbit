import type { Metadata } from "next";
import Link from "next/link";
import { BoxIcon, LayoutGridIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { CreateProjectDialog } from "@/features/projects/components/create-project-dialog";
import type { Project } from "@/features/projects/types";
import { getProjects } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

export const metadata: Metadata = { title: m.title };

/** Home: the projects this person can enter, one per row. */
export default async function HomePage() {
  // The same list the sidebar loaded for this request; no second call to the backend.
  const projects: Project[] = await getProjects();

  return (
    <>
      <PageHeader
        crumbs={[{ label: m.all, icon: <LayoutGridIcon /> }]}
        actions={projects.length > 0 ? <CreateProjectDialog variant="button" /> : null}
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
        <ul className="flex flex-col border-t">
          {projects.map((project) => (
            <li key={project.id} className="border-b">
              <Link
                href={`/project/${project.key}`}
                className="flex h-11 items-center gap-3 px-5 outline-none hover:bg-muted focus-visible:bg-muted max-md:h-14"
              >
                <BoxIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                <span className="truncate font-medium">{project.name}</span>
                <span className="shrink-0 text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
                  {project.key}
                </span>
                <span className="hidden min-w-0 flex-1 truncate text-[0.8125rem] leading-5 text-muted-foreground sm:block">
                  {project.description}
                </span>
                <span className="ml-auto shrink-0 text-[0.8125rem] leading-5 text-muted-foreground">
                  {messages.roles[project.myRole]} · {m.memberCount(project.memberCount)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
