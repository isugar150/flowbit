"use client";

import { useState } from "react";
import { CheckIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { saveGitAccount } from "../api";
import type { GitAccount } from "../types";

const m = messages.git.accounts;

/**
 * Which Git user each member is (D-147), for servers whose events carry no usable e-mail address.
 * Each row saves on its own.
 */
export function GitAccountsPanel({ accounts }: { accounts: GitAccount[] }) {
  return (
    <ul className="flex flex-col divide-y rounded-lg border">
      {accounts.map((account) => (
        <AccountRow key={account.userId} account={account} />
      ))}
    </ul>
  );
}

function AccountRow({ account }: { account: GitAccount }) {
  const [value, setValue] = useState(account.username ?? "");
  const [saved, setSaved] = useState(false);
  const save = useRefreshingMutation(saveGitAccount, () => setSaved(true));
  const changed = value.trim().replace(/^@/, "").toLowerCase() !== (account.username ?? "");
  const id = `git-account-${account.userId}`;

  return (
    <li className="flex flex-col gap-1 px-4 py-2.5">
      <form
        noValidate
        className="flex flex-wrap items-center gap-x-4 gap-y-2"
        onSubmit={(event) => {
          event.preventDefault();
          if (changed) save.mutate({ userId: account.userId, username: value });
        }}
      >
        <label htmlFor={id} className="flex min-w-40 flex-1 flex-col">
          <span className="truncate font-medium">{account.displayName ?? account.loginId}</span>
          {account.displayName ? <span className="truncate text-[0.8125rem] leading-5 text-muted-foreground">{account.loginId}</span> : null}
        </label>
        <div className="flex items-center gap-2">
          <Input
            id={id}
            value={value}
            placeholder={m.placeholder}
            className="h-9 w-48 max-md:h-11"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            aria-invalid={save.isError ? true : undefined}
            onChange={(event) => {
              setValue(event.target.value);
              setSaved(false);
              save.reset();
            }}
          />
          <Button type="submit" variant="outline" className="h-9 max-md:h-11" disabled={!changed || save.isPending}>
            {saved && !changed ? <CheckIcon data-icon="inline-start" /> : null}
            {saved && !changed ? m.saved : m.save}
          </Button>
        </div>
      </form>
      {save.isError ? (
        <p role="alert" className="text-[0.8125rem] leading-5 text-destructive">
          {apiErrorMessage(save.error)}
        </p>
      ) : null}
    </li>
  );
}
