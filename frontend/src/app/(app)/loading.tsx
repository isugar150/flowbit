import { Skeleton } from "@/components/ui/skeleton";

/**
 * Shown in the content panel the moment a link is followed, while the next page's data is
 * fetched. Without it the old page stays on screen until the new one is ready, which feels
 * like the click did nothing. Shaped like a page: header bar, then a column of content.
 */
export default function AppLoading() {
  return (
    <div className="flex flex-1 flex-col" aria-hidden="true">
      <div className="flex h-11 items-center px-5">
        <Skeleton className="h-4 w-32" />
      </div>
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-6 py-10">
        <Skeleton className="h-7 w-48" />
        <Skeleton className="h-4 w-80 max-w-full" />
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-2/3" />
      </div>
    </div>
  );
}
