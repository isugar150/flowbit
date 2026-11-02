import { HeaderSkeleton, SkeletonFill, TextLine } from "@/components/skeletons";
import { Skeleton } from "@/components/ui/skeleton";

/** The header, the line that says what the page is, the two tabs, and a section of request cards (TriageCards). */
export default function TriageLoading() {
  return (
    <>
      <HeaderSkeleton crumbs={["w-16"]} />
      <div aria-hidden="true" className="px-5 py-3">
        <TextLine className="w-96" />
      </div>
      <div aria-hidden="true" className="flex gap-4 px-5 pb-3">
        <Skeleton className="h-5 w-14" />
        <Skeleton className="h-5 w-14" />
      </div>
      <SkeletonFill className="gap-2 border-t px-5 pt-4">
        <div className="flex h-5 shrink-0 items-center">
          <Skeleton className="h-4 w-16" />
        </div>
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }, (_, index) => (
            <div key={index} className="flex flex-col gap-3 rounded-lg border p-4">
              <div className="flex items-center justify-between">
                <Skeleton className="h-3 w-12" />
                <Skeleton className="h-3 w-14" />
              </div>
              <Skeleton className={index % 2 === 0 ? "h-3.5 w-2/3" : "h-3.5 w-1/2"} />
              <Skeleton className="h-3 w-1/3" />
              <div className="flex items-center gap-2">
                <Skeleton className="h-7 w-20 rounded-full" />
                <Skeleton className="h-7 w-14" />
                <Skeleton className="h-7 w-16" />
                <Skeleton className="h-7 w-14" />
              </div>
            </div>
          ))}
        </div>
      </SkeletonFill>
    </>
  );
}
