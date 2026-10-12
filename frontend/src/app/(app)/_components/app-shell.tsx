import Link from "next/link";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import type { Me } from "@/features/auth/types";
import type { Workspace } from "@/features/settings/types";
import { initials } from "@/lib/initials";
import { UserMenu } from "./user-menu";

/**
 * The frame of the signed-in app: a sidebar that is the only global navigation
 * (docs/01-product.md section 3). It lists only what exists; for now that is the workspace
 * (which links home) and the user menu.
 */
export function AppShell({
  user,
  workspace,
  children,
}: {
  user: Me;
  workspace: Workspace;
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-dvh">
      <aside className="hidden w-60 shrink-0 flex-col border-r bg-sidebar text-sidebar-foreground md:flex">
        <div className="flex h-12 items-center px-2">
          <WorkspaceLink workspace={workspace} />
        </div>
        <div className="flex-1" />
        <div className="border-t p-2">
          <UserMenu user={user} placement="sidebar" />
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 items-center justify-between gap-2 border-b px-2 md:hidden">
          <WorkspaceLink workspace={workspace} />
          <UserMenu user={user} placement="header" />
        </header>
        <main className="flex flex-1 flex-col">{children}</main>
      </div>
    </div>
  );
}

function WorkspaceLink({ workspace }: { workspace: Workspace }) {
  return (
    <Link
      href="/"
      className="flex min-w-0 items-center gap-2 rounded-lg px-2 py-1 text-[0.9375rem] font-semibold tracking-[-0.01em] outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      <Avatar size="sm" className="rounded-md after:rounded-md">
        {workspace.logoUrl ? <AvatarImage src={workspace.logoUrl} alt="" className="rounded-md" /> : null}
        <AvatarFallback className="rounded-md bg-primary text-primary-foreground">
          {initials(workspace.name).slice(0, 1)}
        </AvatarFallback>
      </Avatar>
      <span className="truncate">{workspace.name}</span>
    </Link>
  );
}

export function AppShellSkeleton() {
  return (
    <div className="flex min-h-dvh" aria-hidden="true">
      <div className="hidden w-60 shrink-0 flex-col border-r bg-sidebar md:flex">
        <div className="flex h-12 items-center gap-2 px-4">
          <Skeleton className="size-6 rounded-md" />
          <Skeleton className="h-4 w-24" />
        </div>
        <div className="flex-1" />
        <div className="border-t p-2">
          <Skeleton className="h-8 w-full" />
        </div>
      </div>
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex h-12 items-center justify-between border-b px-4 md:hidden">
          <Skeleton className="h-5 w-24" />
          <Skeleton className="h-8 w-20" />
        </div>
      </div>
    </div>
  );
}
