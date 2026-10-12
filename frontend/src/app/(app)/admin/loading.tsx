import { Skeleton } from "@/components/ui/skeleton";

/**
 * Shown under the administrator settings' header and tabs while a tab's data is fetched. Moving
 * between the tabs keeps the layout, so without this the next tab's request data would block the
 * navigation ("uncached data ... outside of <Suspense>").
 */
export default function AdminLoading() {
  return (
    <div className="flex flex-col gap-4" aria-hidden="true">
      <Skeleton className="h-7 w-48" />
      <Skeleton className="h-4 w-80 max-w-full" />
      <Skeleton className="h-9 w-full" />
      <Skeleton className="h-9 w-2/3" />
    </div>
  );
}
