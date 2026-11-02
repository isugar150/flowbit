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

/** What a manager does with a waiting request: set its priority, accept it, mark it a duplicate or decline it. */
function TriageActions({ issue }: { issue: Issue }) {
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
    <>
      <div className="flex flex-wrap items-center gap-2">
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
      </div>
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
    </>
  );
}

/** One request as a card: its number and age, the title that opens it, where it was asked and by whom, and what can be done with it. */
function RequestCard({ issue, byline, children }: { issue: Issue; byline?: string; children?: React.ReactNode }) {
  const origin = [issue.project?.name, byline].filter(Boolean).join(" · ");
  return (
    <li className="flex min-w-0 flex-col gap-3 rounded-lg border bg-background p-4 text-[0.8125rem] leading-5 shadow-xs">
      <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
        <span className="tabular-nums">{issue.key}</span>
        <TimeAgo iso={issue.createdAt} />
      </div>
      <Link href={`/issue/${issue.key}`} className="line-clamp-2 break-words rounded-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
        {issue.title}
      </Link>
      {origin ? <p className="truncate text-xs text-muted-foreground">{origin}</p> : null}
      {children}
    </li>
  );
}

function CardSection({ title, count, hint, children }: { title: string; count: number; hint?: string; children: React.ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-2">
      <h2 className="flex items-baseline gap-2 px-5 text-sm font-semibold">
        {title}
        <span className="text-xs font-normal text-muted-foreground tabular-nums">{count}</span>
      </h2>
      {hint ? <p className="px-5 text-xs leading-5 text-muted-foreground">{hint}</p> : null}
      <ul className="grid gap-3 px-5 sm:grid-cols-2 xl:grid-cols-3">{children}</ul>
    </section>
  );
}

/**
 * The requests as cards: the ones waiting to be sorted in the projects the person manages (or the declined ones) above,
 * the ones the person made themself below, which they follow but cannot sort (docs/features/triage.md).
 */
export function TriageCards({ queue, mine, rejected = false }: { queue: Issue[]; mine: Issue[]; rejected?: boolean }) {
  // A manager's own request is in both lists; it shows once, above, where it can be sorted.
  const queued = new Set(queue.map((issue) => issue.id));
  const requested = mine.filter((issue) => !queued.has(issue.id));
  if (queue.length === 0 && requested.length === 0) {
    return <p className="border-t px-5 py-6 text-[0.8125rem] leading-5 text-muted-foreground">{rejected ? m.rejectedEmpty : m.empty}</p>;
  }
  return (
    <div className="flex flex-col gap-6 border-t pt-4 pb-6">
      {queue.length > 0 ? (
        <CardSection title={rejected ? m.rejectedSection : m.waitingSection} count={queue.length}>
          {queue.map((issue) => (
            <RequestCard key={issue.id} issue={issue} byline={m.requestedBy(personName(issue.createdBy))}>
              {rejected ? null : <TriageActions issue={issue} />}
            </RequestCard>
          ))}
        </CardSection>
      ) : null}
      {requested.length > 0 ? (
        <CardSection title={m.mineSection} count={requested.length} hint={m.mineHint}>
          {requested.map((issue) => (
            <RequestCard key={issue.id} issue={issue} />
          ))}
        </CardSection>
      ) : null}
    </div>
  );
}
