import Link from "next/link";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { TooltipProvider } from "@/components/ui/tooltip";
import type { Me } from "@/features/auth/types";
import type { ProjectSummary } from "@/features/projects/types";
import { RealtimeProvider } from "@/features/realtime/realtime-provider";
import type { Workspace } from "@/features/settings/types";
import { mark } from "@/lib/initials";
import { MobileNav } from "./mobile-nav";
import { SidebarNav } from "./sidebar-nav";
import { UserMenu } from "./user-menu";

/**
 * The frame of the signed-in app (docs/01-product.md section 3). The sidebar sits directly on
 * the app background; the content is a panel with rounded corners inset from the window edge,
 * and it is the panel that scrolls, so the sidebar and the page header never move.
 */
export function AppShell({
  user,
  workspace,
  projects,
  children,
}: {
  user: Me;
  workspace: Workspace;
  projects: ProjectSummary[];
  children: React.ReactNode;
}) {
  return (
    <RealtimeProvider userId={user.id}>
      <TooltipProvider>
        <div className="flex h-dvh bg-sidebar text-foreground">
          <aside className="hidden w-60 shrink-0 flex-col md:flex">
            <div className="flex h-12 items-center px-2">
              <WorkspaceLink workspace={workspace} />
            </div>
            <SidebarNav projects={projects} />
            <div className="p-2">
              <UserMenu user={user} placement="sidebar" />
            </div>
          </aside>
          <div className="flex min-w-0 flex-1 flex-col md:py-2 md:pr-2">
            <div className="flex min-h-0 flex-1 flex-col overflow-hidden bg-background md:rounded-xl md:border">
              <div className="flex h-12 shrink-0 items-center justify-between gap-2 border-b px-2 md:hidden">
                {/* The sidebar is hidden at this width; the menu holds the same places. */}
                <div className="flex min-w-0 items-center">
                  <MobileNav projects={projects} />
                  <WorkspaceLink workspace={workspace} />
                </div>
                <UserMenu user={user} placement="header" />
              </div>
              <main className="flex min-h-0 flex-1 flex-col overflow-y-auto overscroll-contain">
                {children}
              </main>
            </div>
          </div>
        </div>
      </TooltipProvider>
    </RealtimeProvider>
  );
}

function WorkspaceLink({ workspace }: { workspace: Workspace }) {
  return (
    <Link
      href="/"
      className="flex h-8 min-w-0 items-center gap-2 rounded-md px-2 text-[0.8125rem] leading-5 font-semibold outline-none hover:bg-sidebar-accent focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      <Avatar size="sm" className="size-5 rounded-md after:rounded-md">
        {workspace.logoUrl ? (
          <AvatarImage src={workspace.logoUrl} alt="" className="rounded-md" />
        ) : null}
        <AvatarFallback className="rounded-md bg-primary text-[0.625rem] font-semibold text-primary-foreground">
          {mark(workspace.name, 1)}
        </AvatarFallback>
      </Avatar>
      <span className="truncate">{workspace.name}</span>
    </Link>
  );
}

export function AppShellSkeleton() {
  return (
    <div className="flex h-dvh bg-sidebar" aria-hidden="true">
      <div className="hidden w-60 shrink-0 flex-col md:flex">
        <div className="flex h-12 items-center gap-2 px-4">
          <Skeleton className="size-5 rounded-md" />
          <Skeleton className="h-4 w-24" />
        </div>
        <div className="flex flex-1 flex-col gap-2 px-4 py-2">
          <Skeleton className="h-4 w-28" />
          <Skeleton className="h-4 w-20" />
        </div>
        <div className="p-2">
          <Skeleton className="h-8 w-full" />
        </div>
      </div>
      <div className="flex min-w-0 flex-1 flex-col md:py-2 md:pr-2">
        <div className="flex-1 bg-background md:rounded-xl md:border" />
      </div>
    </div>
  );
}
