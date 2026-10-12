import { Suspense } from "react";
import { notFound } from "next/navigation";
import { BoxIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { PillTabs } from "@/components/pill-tabs";
import { Skeleton } from "@/components/ui/skeleton";
import type { Project } from "@/features/projects/types";
import { PresenceAvatars } from "@/features/realtime/components/presence-avatars";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

/**
 * The project's header and tabs, with the avatars of whoever else is looking at the same page. They read the project, so they sit inside <Suspense>: with
 * cacheComponents, request data read outside a boundary blocks every navigation between tabs.
 */
export default function ProjectLayout({ children, params }: LayoutProps<"/project/[key]">) {
  return (
    <>
      <Suspense fallback={<ProjectNavSkeleton />}>
        <ProjectNav params={params} />
      </Suspense>
      {children}
    </>
  );
}

async function ProjectNav({ params }: { params: LayoutProps<"/project/[key]">["params"] }) {
  const { key } = await params;
  // The backend answers "not found" both for a missing project and for one this person may not enter.
  const project = await serverGet<Project>(`/api/v1/projects/${encodeURIComponent(key)}`);
  if (!project) notFound();
  const base = `/project/${project.key}`;

  return (
    <>
      {/* Anything in this project changing re-renders it for everyone who has it open. */}
      <RealtimeScope topics={[`project:${project.key}`]} presence={`project:${project.key}`} />
      <PageHeader
        crumbs={[{ label: m.all, href: "/" }, { label: project.name, icon: <BoxIcon /> }]}
        actions={<PresenceAvatars />}
      />
      <PillTabs
        label={m.tabsLabel}
        tabs={[
          { href: base, label: m.tabOverview },
          { href: `${base}/issues`, label: m.tabIssues },
          { href: `${base}/settings`, label: m.tabSettings },
        ]}
      />
    </>
  );
}

/** Same height as the header and the tabs, so the page does not jump when they arrive. */
function ProjectNavSkeleton() {
  return (
    <div aria-hidden="true">
      <div className="flex h-11 items-center px-4">
        <Skeleton className="h-4 w-40" />
      </div>
      <div className="flex gap-1.5 px-4 pb-2">
        <Skeleton className="h-7 w-14 rounded-full max-md:h-9" />
        <Skeleton className="h-7 w-14 rounded-full max-md:h-9" />
        <Skeleton className="h-7 w-14 rounded-full max-md:h-9" />
      </div>
    </div>
  );
}
