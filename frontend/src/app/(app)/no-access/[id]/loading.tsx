import { HeaderSkeleton, TextLine } from "@/components/skeletons";
import { Skeleton } from "@/components/ui/skeleton";

/** The header, and the notice in the middle of the page that says the project is closed to the person (Empty). */
export default function NoAccessLoading() {
  return (
    <>
      <HeaderSkeleton crumbs={["w-24", "w-32"]} />
      <div aria-hidden="true" className="flex flex-1 items-center justify-center p-6">
        <div className="flex w-full max-w-sm flex-col items-center gap-6">
          <Skeleton className="size-10 rounded-lg" />
          <div className="flex w-full flex-col items-center gap-1.5">
            <TextLine lineHeight="h-6" bar="h-4" className="w-40" />
            <TextLine className="w-64" />
          </div>
          <Skeleton className="h-9 w-32" />
        </div>
      </div>
    </>
  );
}
