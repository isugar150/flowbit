import Link from "next/link";
import { ChevronRightIcon } from "lucide-react";

export type Crumb = {
  label: string;
  href?: string;
  icon?: React.ReactNode;
};

/**
 * The bar at the top of the content panel: where you are, as a path, and the page's actions on
 * the right. The last crumb is the current page and is the page's <h1>. It stays at the top of
 * the panel while the page under it scrolls (the panel's <main> is the scroll box).
 */
export function PageHeader({ crumbs, actions }: { crumbs: Crumb[]; actions?: React.ReactNode }) {
  return (
    <header className="sticky top-0 z-30 flex h-11 shrink-0 items-center justify-between gap-3 bg-background px-4">
      <nav aria-label="breadcrumb" className="flex min-w-0 items-center gap-1 text-[0.8125rem] leading-5">
        {crumbs.map((crumb, index) => {
          const last = index === crumbs.length - 1;
          const content = (
            <>
              {crumb.icon ? <span className="text-muted-foreground [&>svg]:size-4">{crumb.icon}</span> : null}
              <span className="truncate">{crumb.label}</span>
            </>
          );
          return (
            <span key={`${crumb.label}-${index}`} className="flex min-w-0 items-center gap-1">
              {index > 0 ? <ChevronRightIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" /> : null}
              {last ? (
                <h1 className="flex min-w-0 items-center gap-1.5 px-1 font-medium" aria-current="page">
                  {content}
                </h1>
              ) : crumb.href ? (
                <Link
                  href={crumb.href}
                  className="flex min-w-0 items-center gap-1.5 rounded-md px-1 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                >
                  {content}
                </Link>
              ) : (
                <span className="flex min-w-0 items-center gap-1.5 px-1 text-muted-foreground">{content}</span>
              )}
            </span>
          );
        })}
      </nav>
      {actions ? <div className="flex shrink-0 items-center gap-1">{actions}</div> : null}
    </header>
  );
}
