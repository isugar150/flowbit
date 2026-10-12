import { Skeleton } from "@/components/ui/skeleton";

/** Shown under the tabs while a tab's issues are fetched; moving between the tabs keeps the layout. */
export default function MyIssuesLoading() {
  return (
    <div className="flex flex-col gap-3 px-5 py-4" aria-hidden="true">
      <Skeleton className="h-8 w-full" />
      <Skeleton className="h-8 w-full" />
      <Skeleton className="h-8 w-2/3" />
    </div>
  );
}
