import type { Metadata } from "next";
import { InboxIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { TriageQueue } from "@/features/issues/components/triage-queue";
import type { Issue } from "@/features/issues/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.triage;

export const metadata: Metadata = { title: m.title };

/** The issues of the projects the person manages that wait to be accepted, declined or marked a duplicate (docs/features/triage.md). */
export default async function TriagePage() {
  const issues = (await serverGet<Issue[]>("/api/v1/triage")) ?? [];
  return (
    <>
      <RealtimeScope topics={["issues"]} />
      <PageHeader crumbs={[{ label: m.title, icon: <InboxIcon /> }]} />
      <p className="px-5 py-3 text-[0.8125rem] leading-5 text-muted-foreground">{m.description}</p>
      <TriageQueue issues={issues} />
    </>
  );
}
