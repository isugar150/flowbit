import Link from "next/link";
import type { Metadata } from "next";
import { InboxIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { TriageCards } from "@/features/issues/components/triage-queue";
import type { Issue } from "@/features/issues/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.triage;

export const metadata: Metadata = { title: m.title };

const issuesAt = async (path: string) => (await serverGet<Issue[]>(path)) ?? [];

/**
 * The requests as cards: the ones of the projects the person manages that wait to be accepted, declined or marked a
 * duplicate, and under them the requests the person made themself, which anyone may follow here (docs/features/triage.md).
 */
export default async function TriagePage({ searchParams }: { searchParams: Promise<{ rejected?: string }> }) {
  const rejected = (await searchParams).rejected === "true";
  const [queue, mine] = await Promise.all([issuesAt(`/api/v1/triage?rejected=${rejected}`), issuesAt(`/api/v1/triage/mine?rejected=${rejected}`)]);
  const tab = (active: boolean) => (active ? "font-semibold underline underline-offset-4" : "text-muted-foreground");
  return (
    <>
      <RealtimeScope topics={["issues"]} />
      <PageHeader crumbs={[{ label: m.title, icon: <InboxIcon /> }]} />
      <p className="px-5 py-3 text-[0.8125rem] leading-5 text-muted-foreground">{m.description}</p>
      <nav aria-label={m.title} className="flex gap-4 px-5 pb-3 text-sm">
        <Link href="/triage" aria-current={!rejected ? "page" : undefined} className={tab(!rejected)}>{m.pending}</Link>
        <Link href="/triage?rejected=true" aria-current={rejected ? "page" : undefined} className={tab(rejected)}>{m.rejected}</Link>
      </nav>
      <TriageCards key={String(rejected)} queue={queue} mine={mine} rejected={rejected} />
    </>
  );
}
