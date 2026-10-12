import { redirect } from "next/navigation";
import { getCurrentUser } from "@/lib/current-user";
import { messages } from "@/messages";
import { SettingsNav } from "./_components/settings-nav";

export default async function SettingsLayout({ children }: { children: React.ReactNode }) {
  const user = await getCurrentUser();
  if (!user) redirect("/login");

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-6 px-5 py-8 sm:px-8">
      <h1 className="text-xl leading-7 font-semibold">{messages.settings.title}</h1>
      <SettingsNav isAdmin={user.role === "ADMIN"} />
      {children}
    </div>
  );
}
