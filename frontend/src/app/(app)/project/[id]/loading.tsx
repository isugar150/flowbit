import { ProjectOverviewSkeleton } from "@/components/skeletons";

/**
 * Shown under the project's header and tabs while a tab's data is fetched. Moving between tabs
 * keeps this layout, so without it the request data of the next tab would block the navigation.
 * Shaped like the overview, the page people land on.
 */
export default function ProjectLoading() {
  return <ProjectOverviewSkeleton />;
}
