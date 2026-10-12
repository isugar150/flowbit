import { redirect } from "next/navigation";
import { ShieldIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { PillTabs } from "@/components/pill-tabs";
import { requireUser } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.admin;

/**
 * Administrator settings: the workspace and its members. Separate from personal settings
 * (/settings). Turning non-administrators away here is a convenience; the backend refuses
 * every `/api/v1/admin` call from them on its own.
 */
export default async function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const user = await requireUser();
  if (user.role !== "ADMIN") redirect("/");

  return (
    <>
      <PageHeader crumbs={[{ label: m.title, icon: <ShieldIcon /> }]} />
      <PillTabs
        label={m.navLabel}
        tabs={[
          { href: "/admin/workspace", label: m.workspaceTab },
          { href: "/admin/members", label: m.membersTab },
          { href: "/admin/auth", label: m.authTab },
          { href: "/admin/notifications", label: m.notificationsTab },
        ]}
      />
      <div className="mx-auto flex w-full max-w-2xl flex-col gap-8 px-6 py-10">
        {children}
      </div>
    </>
  );
}
