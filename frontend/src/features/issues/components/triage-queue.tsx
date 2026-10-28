"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { TimeAgo } from "@/components/dates";
import { personName } from "@/components/person";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createComment, updateIssue } from "../api";
import { priorityOptions } from "../options";
import type { Issue, IssuePriority } from "../types";
import { ChipSelect } from "./chip-select";
import { AddRelation } from "./issue-relations";

const m = messages.triage;

/** A plain paragraph as an editor document, for the reason a request is declined. */
const textDoc = (text: string) => ({ type: "doc", content: [{ type: "paragraph", content: [{ type: "text", text }] }] });

function TriageRow({ issue, rejected }: { issue: Issue; rejected: boolean }) {
  const [error, setError] = useState<string | null>(null);
  const [declining, setDeclining] = useState(false);
  const [reason, setReason] = useState("");
  const done = () => setError(null);
  const fail = (e: unknown) => setError(apiErrorMessage(e));
  const accept = useRefreshingMutation(updateIssue, done);
  const setPriority = useRefreshingMutation(updateIssue);
  const decline = useRefreshingMutation(async () => {
    await updateIssue({ number: issue.number, changes: { triage: false, status: "CANCELED", triageRejected: true } });
    if (reason.trim()) await createComment({ number: issue.number, content: textDoc(reason.trim()), attachmentIds: [] });
  }, done);
  const close = useRefreshingMutation(updateIssue, done);

  return (
    <li className="flex flex-col gap-2 border-b px-5 py-3 text-[0.8125rem] leading-5">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="text-xs text-muted-foreground tabular-nums">{issue.key}</span>
        <Link href={`/issue/${issue.key}`} className="min-w-0 flex-1 truncate rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
          {issue.title}
        </Link>
        <span className="text-xs text-muted-foreground">
          {issue.project ? `${issue.project.name} · ` : ""}
          {m.requestedBy(personName(issue.createdBy))} · <TimeAgo iso={issue.createdAt} />
        </span>
      </div>
      {!rejected ? <div className="flex flex-wrap items-center gap-2">
        <ChipSelect
          label={m.priorityOf(issue.key)}
          value={issue.priority}
          options={priorityOptions}
          onChange={(priority: IssuePriority) => setPriority.mutate({ number: issue.number, changes: { priority } }, { onError: fail })}
        />
        <Button size="sm" className="max-md:h-11" disabled={accept.isPending} onClick={() => accept.mutate({ number: issue.number, changes: { triage: false } }, { onError: fail })}>
          {m.accept}
        </Button>
        <AddRelation
          number={issue.number}
          fixedKind="DUPLICATE_OF"
          triggerLabel={m.duplicate}
          onAdded={() => close.mutate({ number: issue.number, changes: { triage: false, status: "CANCELED" } }, { onError: fail })}
        />
        <Button variant="outline" size="sm" className="max-md:h-11" onClick={() => setDeclining((current) => !current)}>
          {m.decline}
        </Button>
      </div> : null}
      {declining ? (
        <form
          className="flex flex-col gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            decline.mutate(undefined, { onError: fail });
          }}
        >
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            {m.reasonLabel}
            <textarea
              value={reason}
              maxLength={2000}
              rows={2}
              placeholder={m.reasonPlaceholder}
              onChange={(event) => setReason(event.target.value)}
              className="rounded-md border bg-background p-2 text-[0.8125rem] leading-5 text-foreground outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
            />
          </label>
          <div className="flex gap-2">
            <Button type="submit" variant="destructive" size="sm" disabled={decline.isPending}>
              {m.confirmDecline}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setDeclining(false)}>
              {m.cancel}
            </Button>
          </div>
        </form>
      ) : null}
      {error ? <FormAlert>{error}</FormAlert> : null}
    </li>
  );
}

/** The issues waiting to be sorted, oldest first (docs/features/triage.md). */
export function TriageQueue({ issues, rejected = false }: { issues: Issue[]; rejected?: boolean }) {
  if (issues.length === 0) {
    return <p className="border-t px-5 py-6 text-[0.8125rem] leading-5 text-muted-foreground">{rejected ? m.rejectedEmpty : m.empty}</p>;
  }
  return (
    <div className="flex flex-col">
      <p className="px-5 pb-2 text-[0.8125rem] leading-5 text-muted-foreground">{rejected ? m.rejectedCount(issues.length) : m.waiting(issues.length)}</p>
      <ul className="border-t">
        {issues.map((issue) => (
          <TriageRow key={issue.id} issue={issue} rejected={rejected} />
        ))}
      </ul>
    </div>
  );
}
