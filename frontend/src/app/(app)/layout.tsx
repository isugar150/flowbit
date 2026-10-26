import { Suspense } from "react";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { getProjects, getUnreadNotifications, getUploadLimit, getWorkspace, requireUser } from "@/lib/current-user";
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
  // Five independent reads, sent together.
  const [user, workspace, projects, uploadLimit, unread] = await Promise.all([
    requireUser(),
    getWorkspace(),
    getProjects(),
    getUploadLimit(),
    getUnreadNotifications(),
  ]);
  return (
    <AppShell user={user} workspace={workspace} projects={projects} uploadLimit={uploadLimit} unreadNotifications={unread}>
      {/* The sidebar and the project lists: a project was made or renamed, or someone was let in or taken out. The bell: a notification came in, or was read in another tab (D-216). */}
      <RealtimeScope topics={["projects", `inbox:${user.id}`]} />
      {children}
    </AppShell>
  );
}
