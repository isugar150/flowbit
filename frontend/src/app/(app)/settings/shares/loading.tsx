import { SettingsListSkeleton } from "@/components/skeletons";

/** The share links the person made: a page of them, one line each. */
export default function SharesLoading() {
  return <SettingsListSkeleton rows={8} />;
}
