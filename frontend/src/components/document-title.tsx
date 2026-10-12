"use client";

import { useEffect } from "react";
import { messages } from "@/messages";

/**
 * Names the browser tab after something shown over the page, such as an issue in a dialog, whose
 * route cannot set the title itself (an intercepted route keeps the title of the page behind it).
 * The page's own title comes back when this goes away.
 */
export function DocumentTitle({ title }: { title: string }) {
  useEffect(() => {
    const previous = document.title;
    document.title = `${title} · ${messages.app.name}`;
    return () => {
      document.title = previous;
    };
  }, [title]);
  return null;
}
