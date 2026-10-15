"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { githubCreated, githubInstallations } from "../api";

const m = messages.git.github.returning;

/**
 * Where GitHub sends the browser back (D-146): after the app was made (`created`, with `code` and
 * `state`), or after it was installed (`installed`). Tells the server, then goes back to the Git
 * settings. The address is read when the page opens, not through `useSearchParams`.
 */
export function GitHubReturn({ kind }: { kind: "created" | "installed" }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    // Development renders effects twice; the code GitHub sent can be used only once.
    if (started.current) return;
    started.current = true;
    const query = new URLSearchParams(window.location.search);
    const call =
      kind === "created" ? githubCreated(query.get("code") ?? "", query.get("state") ?? "") : githubInstallations();
    call.then(
      () => {
        router.replace("/admin/git");
        router.refresh();
      },
      (failure: unknown) => setError(apiErrorMessage(failure)),
    );
  }, [kind, router]);

  return (
    <div className="flex flex-col gap-4">
      {error ? (
        <>
          <FormAlert>
            {m.failed} {error}
          </FormAlert>
          <div>
            <Button variant="outline" className="h-9 max-md:h-11" nativeButton={false} render={<Link href="/admin/git" />}>
              {m.back}
            </Button>
          </div>
        </>
      ) : (
        <p role="status" className="text-muted-foreground">
          {kind === "created" ? m.created : m.installed}
        </p>
      )}
    </div>
  );
}
