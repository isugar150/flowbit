import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { LayoutGridIcon, LockIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { Person } from "@/components/person";
import { Button } from "@/components/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { RequestAccessButton } from "@/features/projects/components/request-access-button";
import type { ProjectEntry } from "@/features/projects/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.projects;

/**
 * Where someone lands when they open a project they see in the list but have not been added to
 * (D-047): it says so, names the project's managers, and sends them a request to be let in (D-128).
 */
export default async function NoAccessPage({ params }: PageProps<"/no-access/[id]">) {
  const { id } = await params;
  const entry = await serverGet<ProjectEntry>(`/api/v1/projects/${encodeURIComponent(id)}/access`);
  if (!entry) notFound();
  // Added in the meantime: straight in.
  if (entry.myRole) redirect(`/project/${entry.id}`);

  return (
    <>
      <PageHeader crumbs={[{ label: m.all, href: "/", icon: <LayoutGridIcon /> }, { label: entry.name, icon: <LockIcon /> }]} />
      <div className="flex flex-1 items-center justify-center p-6">
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <LockIcon />
            </EmptyMedia>
            <EmptyTitle>{m.noAccessTitle}</EmptyTitle>
            <EmptyDescription>
              {entry.managers.length > 0 ? m.noAccessDescription(entry.name) : m.noAccessNoManager}
            </EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <RequestAccessButton projectId={entry.id} requestedAt={entry.requestedAt} />
            {entry.managers.length > 0 ? (
              <section aria-label={m.noAccessManagers} className="flex w-full flex-col gap-2 rounded-lg border p-3 text-left">
                <h2 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.noAccessManagers}</h2>
                <ul className="flex flex-col gap-2">
                  {entry.managers.map((manager) => (
                    <li key={manager.userId}>
                      <Person person={manager} />
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
            <Button variant="outline" className="max-md:h-11" nativeButton={false} render={<Link href="/" />}>
              {m.noAccessBack}
            </Button>
          </EmptyContent>
        </Empty>
      </div>
    </>
  );
}
