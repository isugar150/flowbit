import { HeaderSkeleton, IssueListSkeleton, ListToolbarSkeleton } from "@/components/skeletons";

/** Shown while the page reads its data: the header, the tool line and the lines of the list, as they will be. */
export default function PageLoading() {
  return (
    <>
      <HeaderSkeleton crumbs={["w-20"]} />
      <ListToolbarSkeleton />
      <IssueListSkeleton />
    </>
  );
}
