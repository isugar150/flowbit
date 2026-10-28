import { ProjectSettingsBodySkeleton } from "@/components/skeletons";

/** The settings tab: the index and the sections, in the column the page has (project/[id]/settings/page.tsx). */
export default function ProjectSettingsLoading() {
  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-10 px-6 py-10">
      <ProjectSettingsBodySkeleton />
    </div>
  );
}
