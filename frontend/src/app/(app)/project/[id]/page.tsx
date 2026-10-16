import { ProjectOverview } from "@/features/projects/components/project-overview";

/** The project's overview (features/projects/components/project-overview.tsx). */
export default async function ProjectOverviewPage({ params }: PageProps<"/project/[id]">) {
  const { id } = await params;
  return <ProjectOverview id={id} />;
}
