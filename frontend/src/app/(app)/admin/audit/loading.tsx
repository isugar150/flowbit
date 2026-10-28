import { SectionHeadingSkeleton } from "@/components/skeletons";
import { Skeleton } from "@/components/ui/skeleton";

/** The log: the filter, a page of lines and the page numbers, under the heading. */
export default function AdminAuditLoading() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-6">
      <SectionHeadingSkeleton />
      <div className="flex flex-wrap gap-2">
        <Skeleton className="h-9 w-40" />
        <Skeleton className="h-9 w-40" />
        <Skeleton className="h-9 w-36" />
      </div>
      <div className="flex flex-col rounded-lg border">
        {Array.from({ length: 12 }, (_, index) => (
          <div key={index} className="flex h-11 items-center gap-3 border-b px-3 last:border-b-0">
            <Skeleton className="h-3.5 w-24 shrink-0" />
            <Skeleton className="h-3.5 w-20 shrink-0" />
            <Skeleton className="h-3.5 w-1/3" />
          </div>
        ))}
      </div>
    </div>
  );
}
