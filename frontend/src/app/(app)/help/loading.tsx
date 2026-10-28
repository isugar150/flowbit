import { HeaderSkeleton, ParagraphsSkeleton, TextLine } from "@/components/skeletons";
import { Skeleton } from "@/components/ui/skeleton";

/** The help page: the table of contents beside the heading, the cards to start from, and the first sections (HelpContent). */
export default function HelpLoading() {
  return (
    <>
      <HeaderSkeleton crumbs={["w-16"]} />
      <div aria-hidden="true" className="mx-auto flex w-full max-w-6xl flex-col gap-10 px-6 py-10 lg:flex-row lg:items-start lg:gap-14">
        <div className="flex flex-col gap-1 max-lg:hidden lg:w-56 lg:shrink-0">
          {Array.from({ length: 14 }, (_, index) => (
            <div key={index} className="flex h-7 items-center">
              <Skeleton className={index % 3 === 0 ? "h-3.5 w-32" : "h-3.5 w-24"} />
            </div>
          ))}
        </div>
        <div className="flex min-w-0 max-w-3xl flex-1 flex-col gap-16">
          <div className="flex flex-col gap-6">
            <div className="flex flex-col gap-1.5">
              <TextLine lineHeight="h-7" bar="h-5" className="w-40" />
              <TextLine className="w-96" />
            </div>
            <div className="flex flex-col gap-2">
              <TextLine bar="h-3" className="w-20" />
              <div className="grid grid-cols-2 gap-2">
                {Array.from({ length: 4 }, (_, index) => (
                  <Skeleton key={index} className="h-20 rounded-xl max-sm:h-14" />
                ))}
              </div>
            </div>
          </div>
          {Array.from({ length: 2 }, (_, index) => (
            <div key={index} className="flex flex-col gap-10">
              <div className="flex flex-col gap-1">
                <TextLine lineHeight="h-7" bar="h-5" className="w-48" />
                <TextLine className="w-80" />
              </div>
              <div className="flex flex-col gap-5 border-t pt-8">
                <div className="flex items-start gap-3">
                  <Skeleton className="size-9 shrink-0 rounded-lg" />
                  <div className="flex flex-col">
                    <TextLine lineHeight="h-6" bar="h-4" className="w-40" />
                    <TextLine className="w-72" />
                  </div>
                </div>
                <ParagraphsSkeleton paragraphs={3} />
              </div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}
