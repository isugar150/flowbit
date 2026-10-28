import { HeaderSkeleton, SettingsSectionSkeleton } from "@/components/skeletons";

/**
 * Shown in the content panel the moment a link is followed, while the next page's data is
 * fetched. Without it the old page stays on screen until the new one is ready, which feels
 * like the click did nothing. Only the pages without a `loading.tsx` of their own land here, so it is
 * the shape every page has: a header bar, then a column of content.
 */
export default function AppLoading() {
  return (
    <>
      <HeaderSkeleton />
      <div aria-hidden="true" className="mx-auto flex w-full max-w-3xl flex-col gap-8 px-6 py-10">
        <SettingsSectionSkeleton fields={4} />
      </div>
    </>
  );
}
