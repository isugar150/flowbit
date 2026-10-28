import { SettingsSectionSkeleton } from "@/components/skeletons";

/**
 * Shown under the administrator settings' header and tabs while a tab's data is fetched. Moving
 * between the tabs keeps the layout, so without this the next tab's request data would block the
 * navigation ("uncached data ... outside of <Suspense>"). The tabs that look different (a list, cards, a
 * table) have a `loading.tsx` of their own; this is the one of a form.
 */
export default function AdminLoading() {
  return <SettingsSectionSkeleton fields={3} />;
}
