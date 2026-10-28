import { Separator } from "@/components/ui/separator";
import { SettingsSectionSkeleton } from "@/components/skeletons";

/** The personal settings under their header and tabs: the blocks the page has, one under the other. */
export default function SettingsLoading() {
  return (
    <>
      <SettingsSectionSkeleton fields={3} />
      <Separator />
      <SettingsSectionSkeleton fields={2} />
      <Separator />
      <SettingsSectionSkeleton fields={2} />
    </>
  );
}
