import { IssueListSkeleton, ListToolbarSkeleton } from "@/components/skeletons";

/** Shown under the tabs while a tab's issues are fetched; moving between the tabs keeps the layout. */
export default function MyIssuesLoading() {
  return (
    <>
      <ListToolbarSkeleton />
      <IssueListSkeleton />
    </>
  );
}
