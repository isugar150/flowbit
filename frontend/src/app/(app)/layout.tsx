import { Suspense } from "react";
import { redirect } from "next/navigation";
import { getCurrentUser } from "@/lib/current-user";
import { AppShell, AppShellSkeleton } from "./_components/app-shell";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<AppShellSkeleton />}>
      <SignedInShell>{children}</SignedInShell>
    </Suspense>
  );
}

async function SignedInShell({ children }: { children: React.ReactNode }) {
  const user = await getCurrentUser();
  // proxy.ts already redirects signed-out visitors; this covers a session that ended in between.
  if (!user) redirect("/login");
  return <AppShell user={user}>{children}</AppShell>;
}
