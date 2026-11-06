"use client";

import { useState } from "react";

/** A fresh notification opens a resolved conversation, even if the previous visit was folded by hand. */
export function useCommentExpansion(resolved: boolean, resolutionKey: string, focused: boolean, navigationKey: string) {
  const scope = `${resolutionKey}\n${focused ? navigationKey : ""}`;
  const [choice, setChoice] = useState<{ scope: string; expanded: boolean } | null>(null);
  const expanded = !resolved || (choice?.scope === scope ? choice.expanded : focused);
  return {
    expanded,
    setExpanded: (next: boolean) => setChoice({ scope, expanded: next }),
    reset: () => setChoice(null),
  };
}
