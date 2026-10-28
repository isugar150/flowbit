import { DocumentSkeleton, HeaderSkeleton } from "@/components/skeletons";

/** Shown while the document is read: the path above it, then the title and the body. */
export default function PageLoading() {
  return (
    <>
      <HeaderSkeleton crumbs={["w-24", "w-32"]} />
      <DocumentSkeleton />
    </>
  );
}
