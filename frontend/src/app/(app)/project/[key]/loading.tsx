import { Skeleton } from "@/components/ui/skeleton";

/**
 * Shown under the project's header and tabs while a tab's data is fetched. Moving between tabs
 * keeps this layout, so without it the request data of the next tab would block the navigation.
 */
export default function ProjectLoading() {
  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-6 py-10" aria-hidden="true">
      <Skeleton className="h-7 w-48" />
      <Skeleton className="h-4 w-80 max-w-full" />
      <Skeleton className="h-9 w-full" />
      <Skeleton className="h-9 w-full" />
      <Skeleton className="h-9 w-2/3" />
    </div>
  );
}
