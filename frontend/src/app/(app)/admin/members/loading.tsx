import { SettingsListSkeleton } from "@/components/skeletons";

/** The members page lays two panes side by side, so it marks itself wide as the page does (data-wide). */
export default function AdminMembersLoading() {
  return (
    <div data-wide>
      <SettingsListSkeleton rows={8} />
    </div>
  );
}
