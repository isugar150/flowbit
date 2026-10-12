import { notFound } from "next/navigation";
import { BoxIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { PillTabs } from "@/components/pill-tabs";
import type { Project } from "@/features/projects/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

export default async function ProjectLayout({ children, params }: LayoutProps<"/project/[key]">) {
  const { key } = await params;
  // The backend answers "not found" both for a missing project and for one this person may not enter.
  const project = await serverGet<Project>(`/api/v1/projects/${encodeURIComponent(key)}`);
  if (!project) notFound();
  const base = `/project/${project.key}`;

  return (
    <>
      <PageHeader crumbs={[{ label: m.all, href: "/" }, { label: project.name, icon: <BoxIcon /> }]} />
      <PillTabs
        label={m.tabsLabel}
        tabs={[
          { href: base, label: m.tabOverview },
          { href: `${base}/issues`, label: m.tabIssues },
          { href: `${base}/settings`, label: m.tabSettings },
        ]}
      />
      {children}
    </>
  );
}
