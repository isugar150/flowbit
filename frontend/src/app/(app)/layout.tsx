import { Suspense } from "react";
import { getProjects, getWorkspace, requireUser } from "@/lib/current-user";
import { AppShell, AppShellSkeleton } from "./_components/app-shell";

/** `modal` is the `@modal` slot: an issue opened from inside the app, as a dialog over the page. */
export default function AppLayout({ children, modal }: LayoutProps<"/">) {
  return (
    <Suspense fallback={<AppShellSkeleton />}>
      <SignedInShell>
        {children}
        {modal}
      </SignedInShell>
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
