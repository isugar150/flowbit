import { UserIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { PillTabs } from "@/components/pill-tabs";
import { messages } from "@/messages";

const m = messages.settings;

/**
 * Personal settings: things each person decides for themselves. Administration lives under /admin. The tabs split what would
 * be too long for one page (the share links the person made can be many, D-204).
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <PageHeader crumbs={[{ label: m.title, icon: <UserIcon /> }]} />
      <PillTabs
        label={m.tabsLabel}
        tabs={[
          { href: "/settings", label: m.tabProfile },
          { href: "/settings/shares", label: m.tabShares },
          { href: "/settings/mcp", label: messages.mcp.title },
        ]}
      />
      <div className="mx-auto flex w-full max-w-2xl flex-col gap-8 px-6 py-10">{children}</div>
    </>
  );
}
