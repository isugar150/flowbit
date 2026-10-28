import { HeaderSkeleton, IssueListSkeleton, ListToolbarSkeleton } from "@/components/skeletons";

/** An issue's address opened directly stands over the list of all issues, so the list is what waits. */
export default function PageLoading() {
  return (
    <>
      <HeaderSkeleton crumbs={["w-20"]} />
      <ListToolbarSkeleton />
      <IssueListSkeleton />
    </>
  );
}
