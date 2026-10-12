import { Skeleton } from "@/components/ui/skeleton";

/** Shown while the page reads its data. */
export default function PageLoading() {
  return (
    <div className="flex flex-col gap-3 px-5 py-4" aria-hidden="true">
      <Skeleton className="h-8 w-full" />
      <Skeleton className="h-8 w-full" />
      <Skeleton className="h-8 w-2/3" />
    </div>
  );
}
