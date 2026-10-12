"use client";

import { useInBrowser } from "@/components/dates";

/** True on macOS, iPhone and iPad, where shortcuts use ⌘ instead of Ctrl. */
function isApplePlatform(): boolean {
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform);
}

/**
 * The modifier key of a shortcut as the person's keyboard names it: ⌘ on a Mac, Ctrl elsewhere.
 * The server cannot know, so it renders Ctrl and the browser corrects it.
 */
export function ModKey() {
  const inBrowser = useInBrowser();
  return <>{inBrowser && isApplePlatform() ? "⌘" : "Ctrl"}</>;
}
