import { Wordmark } from "@/components/brand/wordmark";
import { Skeleton } from "@/components/ui/skeleton";
import type { Me } from "@/features/auth/types";
import { UserMenu } from "./user-menu";

/**
 * The frame of the signed-in app: a sidebar that is the only global navigation
 * (docs/01-product.md section 3). It lists only what exists; for now that is the user menu.
 */
export function AppShell({ user, children }: { user: Me; children: React.ReactNode }) {
  return (
    <div className="flex min-h-dvh">
      <aside className="hidden w-60 shrink-0 flex-col border-r bg-sidebar text-sidebar-foreground md:flex">
        <div className="flex h-12 items-center px-4">
          <Wordmark />
        </div>
        <div className="flex-1" />
        <div className="border-t p-2">
          <UserMenu user={user} placement="sidebar" />
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 items-center justify-between border-b pr-2 pl-4 md:hidden">
          <Wordmark />
          <UserMenu user={user} placement="header" />
        </header>
        <main className="flex flex-1 flex-col">{children}</main>
      </div>
    </div>
  );
}

export function AppShellSkeleton() {
  return (
    <div className="flex min-h-dvh" aria-hidden="true">
      <div className="hidden w-60 shrink-0 flex-col border-r bg-sidebar md:flex">
        <div className="flex h-12 items-center px-4">
          <Skeleton className="h-5 w-24" />
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
