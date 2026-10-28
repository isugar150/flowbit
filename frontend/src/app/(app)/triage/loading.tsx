import { HeaderSkeleton, SkeletonFill, TextLine } from "@/components/skeletons";
import { Skeleton } from "@/components/ui/skeleton";

/** The header, the line that says what the page is, the two lists to pick from, and the requests (TriageQueue). */
export default function TriageLoading() {
  return (
    <>
      <HeaderSkeleton crumbs={["w-16"]} />
      <div aria-hidden="true" className="px-5 py-3">
        <TextLine className="w-96" />
      </div>
      <div aria-hidden="true" className="flex gap-4 px-5 pb-3">
        <Skeleton className="h-5 w-16" />
        <Skeleton className="h-5 w-14" />
      </div>
      <SkeletonFill>
        {Array.from({ length: 10 }, (_, index) => (
          <div key={index} className="flex shrink-0 flex-col gap-2 border-b px-5 py-3">
            <div className="flex items-center gap-3">
              <Skeleton className="h-3 w-12 shrink-0" />
              <Skeleton className={index % 2 === 0 ? "h-3.5 w-1/3" : "h-3.5 w-1/2"} />
            </div>
            <div className="flex items-center gap-2">
              <Skeleton className="h-7 w-20 rounded-full" />
              <Skeleton className="h-7 w-14" />
              <Skeleton className="h-7 w-16" />
              <Skeleton className="h-7 w-14" />
            </div>
          </div>
        ))}
      </SkeletonFill>
    </>
  );
}
