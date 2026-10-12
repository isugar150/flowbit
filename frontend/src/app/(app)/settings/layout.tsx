import { UserIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { messages } from "@/messages";

/** Personal settings: things each person decides for themselves. Administration lives under /admin. */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <PageHeader crumbs={[{ label: messages.settings.title, icon: <UserIcon /> }]} />
      <div className="mx-auto flex w-full max-w-2xl flex-col gap-8 px-6 py-10">{children}</div>
    </>
  );
}
