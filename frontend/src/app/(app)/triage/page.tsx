import Link from "next/link";
import type { Metadata } from "next";
import { InboxIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { MyRequests, TriageQueue } from "@/features/issues/components/triage-queue";
import type { Issue } from "@/features/issues/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.triage;

export const metadata: Metadata = { title: m.title };

type View = "pending" | "rejected" | "mine";

const issuesAt = async (path: string) => (await serverGet<Issue[]>(path)) ?? [];

/** The requests the person made themself: waiting ones first, then the declined ones. */
const requestsOf = async () => ({
  waiting: await issuesAt("/api/v1/triage/mine?rejected=false"),
  declined: await issuesAt("/api/v1/triage/mine?rejected=true"),
});

/**
 * The issues of the projects the person manages that wait to be accepted, declined or marked a duplicate, and the
 * requests the person made themself, which anyone may follow here (docs/features/triage.md).
 */
export default async function TriagePage({ searchParams }: { searchParams: Promise<{ rejected?: string; mine?: string }> }) {
  const params = await searchParams;
  let view: View = params.mine === "true" ? "mine" : params.rejected === "true" ? "rejected" : "pending";
  const issues = view === "mine" ? [] : await issuesAt(`/api/v1/triage?rejected=${view === "rejected"}`);
  let requests = view === "mine" ? await requestsOf() : null;
  // Opened from the sidebar by someone with nothing to sort, the list that has something in it comes first.
  if (params.mine === undefined && params.rejected === undefined && issues.length === 0) {
    const own = await requestsOf();
    if (own.waiting.length > 0 || own.declined.length > 0) {
      view = "mine";
      requests = own;
    }
  }
  const tab = (active: boolean) => (active ? "font-semibold underline underline-offset-4" : "text-muted-foreground");
  return (
    <>
      <RealtimeScope topics={["issues"]} />
      <PageHeader crumbs={[{ label: m.title, icon: <InboxIcon /> }]} />
      <p className="px-5 py-3 text-[0.8125rem] leading-5 text-muted-foreground">{m.description}</p>
      <nav aria-label={m.title} className="flex gap-4 px-5 pb-3 text-sm">
        <Link href="/triage?rejected=false" aria-current={view === "pending" ? "page" : undefined} className={tab(view === "pending")}>{m.pending}</Link>
        <Link href="/triage?rejected=true" aria-current={view === "rejected" ? "page" : undefined} className={tab(view === "rejected")}>{m.rejected}</Link>
        <Link href="/triage?mine=true" aria-current={view === "mine" ? "page" : undefined} className={tab(view === "mine")}>{m.mine}</Link>
      </nav>
      {requests ? <MyRequests waiting={requests.waiting} declined={requests.declined} /> : <TriageQueue key={view} issues={issues} rejected={view === "rejected"} />}
    </>
  );
}
