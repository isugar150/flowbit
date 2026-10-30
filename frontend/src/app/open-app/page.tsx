"use client";

import { useEffect, useSyncExternalStore } from "react";
import Link from "next/link";
import { appLinkTarget } from "@/features/notifications/app-link";
import { messages } from "@/messages";

const subscribe = () => () => {};
const serverTarget = () => null;
function browserTarget() {
  const parameters = new URLSearchParams(window.location.search);
  return (parameters.getAll("url").length === 1
    ? appLinkTarget(parameters.get("url"), window.location.origin)
    : null) ?? "";
}

/** The browser substitutes the escaped protocol URL for the manifest's %s. */
export default function OpenAppPage() {
  const target = useSyncExternalStore(subscribe, browserTarget, serverTarget);
  const invalid = target === "";
  useEffect(() => {
    if (target) window.location.replace(target);
  }, [target]);

  const m = messages.app.protocolLink;
  return (
    <main className="flex min-h-dvh items-center justify-center p-6">
      <div className="flex max-w-md flex-col gap-3 text-sm">
        <p role={invalid ? "alert" : "status"}>{invalid ? m.invalid : m.opening}</p>
        <Link href="/my-issues" className="w-fit underline underline-offset-4">{m.home}</Link>
      </div>
    </main>
  );
}
