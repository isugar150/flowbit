import type { Metadata } from "next";
import { CircleHelpIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { HelpContent } from "@/features/help/components/help-content";
import { requireUser } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.help;

export const metadata: Metadata = { title: m.title };

/** How to use Flowbit, on one page with a table of contents (docs/features/help.md). */
export default async function HelpPage() {
  const user = await requireUser();

  return (
    <>
      <PageHeader crumbs={[{ label: m.title, icon: <CircleHelpIcon /> }]} />
      <HelpContent isAdmin={user.role === "ADMIN"} />
    </>
  );
}
