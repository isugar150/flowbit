"use client";

import { useEffect, useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { authorizeMyGitHub, finishMyGitHub, unlinkMyGitHub } from "../api";
import type { MyGitHubAccount } from "../types";

const m = messages.git.myAccount;

/**
 * The member's own GitHub account (D-150): connecting it sends the browser to GitHub, which asks
 * them and sends them back to /settings/github/callback. GitHub events by that account are theirs.
 */
export function MyGitHubAccountSetting({ account }: { account: MyGitHubAccount }) {
  const authorize = useMutation({
    mutationFn: () => authorizeMyGitHub(window.location.origin),
    onSuccess: ({ url }) => window.location.assign(url),
  });
  const unlink = useRefreshingMutation(unlinkMyGitHub);
  const failure = [authorize, unlink].find((mutation) => mutation.isError);

  return (
    <div className="flex flex-col gap-3">
      {failure ? <FormAlert>{apiErrorMessage(failure.error)}</FormAlert> : null}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <span className={account.login ? "font-medium" : "text-muted-foreground"}>{account.login ? m.linked(account.login) : m.notLinked}</span>
        {account.login ? (
          <Button type="button" variant="outline" className="h-9 max-md:h-11" disabled={unlink.isPending} onClick={() => unlink.mutate()}>
            {m.unlink}
          </Button>
        ) : (
          <Button type="button" className="h-9 max-md:h-11" disabled={authorize.isPending} onClick={() => authorize.mutate()}>
            {authorize.isPending ? m.connecting : m.connect}
          </Button>
        )}
      </div>
    </div>
  );
}

/** Where GitHub sends the browser back after the member answered; reads the address when it opens. */
export function MyGitHubCallback() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    // Development renders effects twice; GitHub's code can be used only once.
    if (started.current) return;
    started.current = true;
    const query = new URLSearchParams(window.location.search);
    // The member said no on GitHub: GitHub sends an error instead of a code.
    const denied = query.get("error") ? (query.get("error_description") ?? m.denied) : null;
    const finish = denied ? Promise.reject(new Error(denied)) : finishMyGitHub(query.get("code") ?? "", query.get("state") ?? "");
    finish.then(
      () => {
        router.replace("/settings");
        router.refresh();
      },
      (failure: unknown) => setError(denied ?? apiErrorMessage(failure)),
    );
  }, [router]);

  return error ? (
    <div className="flex flex-col gap-4">
      <FormAlert>
        {m.failed} {error}
      </FormAlert>
      <div>
        <Button variant="outline" className="h-9 max-md:h-11" nativeButton={false} render={<Link href="/settings" />}>
          {m.back}
        </Button>
      </div>
    </div>
  ) : (
    <p role="status" className="text-muted-foreground">
      {m.finishing}
    </p>
  );
}
