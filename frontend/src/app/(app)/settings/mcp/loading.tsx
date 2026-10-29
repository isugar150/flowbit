import { SettingsSectionSkeleton } from "@/components/skeletons";
import { Separator } from "@/components/ui/separator";

export default function McpLoading() {
  return <><SettingsSectionSkeleton fields={2} /><Separator /><SettingsSectionSkeleton fields={3} /><Separator /><SettingsSectionSkeleton fields={3} /><Separator /><SettingsSectionSkeleton fields={3} /></>;
}
