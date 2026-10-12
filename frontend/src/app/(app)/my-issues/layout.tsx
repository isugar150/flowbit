import type { Metadata } from "next";
import { PageHeader } from "@/components/page-header";
import { PillTabs } from "@/components/pill-tabs";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { messages } from "@/messages";

const m = messages.myIssues;

export const metadata: Metadata = { title: m.title };

/** "내 이슈": the header and the two tabs. Each tab is a page of its own under this layout. */
export default function MyIssuesLayout({ children }: LayoutProps<"/my-issues">) {
  return (
    <>
      <RealtimeScope topics={["issues"]} />
      <PageHeader crumbs={[{ label: m.title }]} />
      <PillTabs
        label={m.tabsLabel}
        tabs={[
          { href: "/my-issues", label: m.assigned },
          { href: "/my-issues/created", label: m.created },
        ]}
      />
      {children}
    </>
  );
}
