import { Suspense } from "react";
import { getProjects, getWorkspace, requireUser } from "@/lib/current-user";
import { AppShell, AppShellSkeleton } from "./_components/app-shell";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<AppShellSkeleton />}>
      <SignedInShell>{children}</SignedInShell>
    </Suspense>
  );
}

async function SignedInShell({ children }: { children: React.ReactNode }) {
  // Three independent reads, sent together.
  const [user, workspace, projects] = await Promise.all([requireUser(), getWorkspace(), getProjects()]);
  return (
    <AppShell user={user} workspace={workspace} projects={projects}>
      {children}
    </AppShell>
  );
}
