"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronRightIcon, FileTextIcon, LockIcon } from "lucide-react";
import { RichTextEditor } from "@/components/editor/rich-text-editor";
import { PriorityIcon, StatusIcon, priorityLabel, statusLabel } from "@/features/issues/meta";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { documentAddress, issueAddress } from "../links";
import type { SharedDocument, SharedDocumentRef, SharedIssue, SharedIssueRef } from "../types";

const m = messages.share.page.issue;

/** The path from the shared issue down to the page that is open: every step but the last one leads back. */
function Trail({ token, root, trail, current }: { token: string; root: number; trail: SharedIssueRef[]; current: string }) {
  return (
    <nav aria-label={m.trailLabel}>
      <ol className="flex flex-wrap items-center gap-x-1 gap-y-0.5 text-[0.8125rem] leading-5 text-muted-foreground">
        {trail.map((step) => (
          <li key={step.number} className="flex min-w-0 items-center gap-1">
            <Link
              href={issueAddress(token, root, step.number)}
              prefetch={false}
              className="flex min-w-0 max-w-64 items-center gap-1.5 rounded-sm outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
              title={step.number === root ? m.sharedIssue : undefined}
            >
              <span className="shrink-0 tabular-nums">{step.key}</span>
              <span className="truncate">{step.title}</span>
            </Link>
            <ChevronRightIcon className="size-3.5 shrink-0" aria-hidden="true" />
          </li>
        ))}
        <li aria-current="page" className="min-w-0 max-w-64 truncate text-foreground">
          {current}
        </li>
      </ol>
    </nav>
  );
}

/** The text of an issue or a document, read-only. A document block opens the document inside the link. */
function Body({ token, content, documents, label }: { token: string; content: SharedIssue["content"]; documents: SharedDocumentRef[]; label: string }) {
  const router = useRouter();
  return (
    <RichTextEditor
      initialContent={content}
      editable={false}
      ariaLabel={label}
      documents={documents}
      openDocument={(id) => router.push(documentAddress(token, id))}
      documentHref={(id) => documentAddress(token, id)}
    />
  );
}

/** One line of the lists under a page: an issue or a document, which opens inside the link. */
function Row({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <li>
      <Link
        href={href}
        prefetch={false}
        className="flex min-h-10 items-center gap-2 rounded-md px-2 py-1.5 text-[0.8125rem] leading-5 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        {children}
      </Link>
    </li>
  );
}

function Property({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-xs leading-4 text-muted-foreground">{label}</dt>
      <dd className="flex min-w-0 flex-wrap items-center gap-1.5 text-[0.8125rem] leading-5">{children}</dd>
    </div>
  );
}

const day = (iso: string) => iso.slice(0, 10);

/**
 * A shared issue, or a sub-issue of it (docs/features/sharing.md, "티켓 공유"): its properties, its text, and the lists of its sub-issues
 * and documents, all of which open inside the link. Nothing here changes anything.
 */
export function SharedIssuePage({ token, issue }: { token: string; issue: SharedIssue }) {
  const dates = issue.startDate && issue.dueDate ? `${issue.startDate} ~ ${issue.dueDate}` : issue.dueDate;
  return (
    <article className="flex flex-col gap-5 px-4 pt-3 pb-6">
      <Trail token={token} root={issue.rootNumber} trail={issue.trail} current={`${issue.key} ${issue.title}`} />
      <div className="flex flex-col gap-1">
        <p className="flex items-center gap-1.5 text-xs leading-4 text-muted-foreground">
          <span className="tabular-nums">{issue.key}</span>
          <span aria-hidden="true">·</span>
          <LockIcon className="size-3" aria-hidden="true" />
          {m.readOnly}
        </p>
        <h1 className="text-[1.375rem] leading-[1.875rem] font-semibold">{issue.title}</h1>
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-3 rounded-lg border px-3 py-3 sm:grid-cols-3">
        <Property label={m.status}>
          <StatusIcon status={issue.status} />
          {statusLabel(issue.status)}
        </Property>
        <Property label={m.priority}>
          <PriorityIcon priority={issue.priority} />
          {priorityLabel(issue.priority)}
        </Property>
        {dates ? (
          <Property label={m.dueDate}>
            <span className="tabular-nums">{dates}</span>
          </Property>
        ) : null}
        {issue.assignees.length > 0 ? <Property label={m.assignees}>{issue.assignees.join(", ")}</Property> : null}
        {issue.project ? <Property label={m.project}>{issue.project}</Property> : null}
        {issue.createdBy ? <Property label={m.createdBy}>{issue.createdBy}</Property> : null}
        <Property label={m.createdAt}>
          <span className="tabular-nums">{day(issue.createdAt)}</span>
        </Property>
        <Property label={m.updatedAt}>
          <span className="tabular-nums">{day(issue.updatedAt)}</span>
        </Property>
        {issue.labels.length > 0 ? (
          <Property label={messages.issues.labels}>
            {issue.labels.map((label) => (
              <span key={label.name} className="flex h-6 max-w-40 items-center gap-1.5 rounded-full border px-2 text-xs leading-4">
                <span aria-hidden="true" className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: label.color }} />
                <span className="truncate">{label.name}</span>
              </span>
            ))}
          </Property>
        ) : null}
      </dl>
      {issue.content ? (
        <Body key={issue.number} token={token} content={issue.content} documents={issue.documents} label={m.bodyLabel} />
      ) : (
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.noBody}</p>
      )}
      {issue.subIssues.length > 0 ? (
        <section aria-label={m.subIssues} className="flex flex-col gap-1">
          <h2 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.subIssues}</h2>
          <ul className="flex flex-col">
            {issue.subIssues.map((sub) => (
              <Row key={sub.number} href={issueAddress(token, issue.rootNumber, sub.number)}>
                <StatusIcon status={sub.status} />
                <span className="shrink-0 text-muted-foreground tabular-nums">{sub.key}</span>
                <span className={cn("min-w-0 flex-1 truncate", (sub.status === "DONE" || sub.status === "CANCELED") && "text-muted-foreground line-through")}>
                  {sub.title}
                </span>
                {sub.subIssueCount > 0 ? (
                  <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{m.subProgress(sub.subIssueDoneCount, sub.subIssueCount)}</span>
                ) : null}
                {sub.dueDate ? <span className="hidden shrink-0 text-xs text-muted-foreground tabular-nums sm:block">{sub.dueDate}</span> : null}
              </Row>
            ))}
          </ul>
        </section>
      ) : null}
      {issue.documents.length > 0 ? <DocumentList token={token} documents={issue.documents} /> : null}
    </article>
  );
}

function DocumentList({ token, documents }: { token: string; documents: SharedDocumentRef[] }) {
  return (
    <section aria-label={m.documents} className="flex flex-col gap-1">
      <h2 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{m.documents}</h2>
      <ul className="flex flex-col">
        {documents.map((document) => (
          <Row key={document.id} href={documentAddress(token, document.id)}>
            <FileTextIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">{document.title || m.untitledDocument}</span>
            <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{day(document.updatedAt)}</span>
          </Row>
        ))}
      </ul>
    </section>
  );
}

/** A document of a shared issue or of one of its sub-issues, read-only, with the issue it belongs to above it. */
export function SharedDocumentPage({ token, document }: { token: string; document: SharedDocument }) {
  const title = document.title || m.untitledDocument;
  return (
    <article className="flex flex-col gap-5 px-4 pt-3 pb-6">
      <Trail token={token} root={document.rootNumber} trail={document.trail} current={title} />
      <div className="flex flex-col gap-1">
        <p className="flex items-center gap-1.5 text-xs leading-4 text-muted-foreground">
          <FileTextIcon className="size-3" aria-hidden="true" />
          {m.documentTitle}
          <span aria-hidden="true">·</span>
          <LockIcon className="size-3" aria-hidden="true" />
          {m.readOnly}
        </p>
        <h1 className="text-[1.375rem] leading-[1.875rem] font-semibold">{title}</h1>
        <Link
          href={issueAddress(token, document.rootNumber, document.issue.number)}
          prefetch={false}
          className="flex w-fit max-w-full items-center gap-1.5 rounded-sm text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <span>{m.documentOf}</span>
          <span className="shrink-0 tabular-nums">{document.issue.key}</span>
          <span className="truncate">{document.issue.title}</span>
        </Link>
      </div>
      <Body key={document.id} token={token} content={document.content} documents={document.documents} label={m.documentBodyLabel} />
    </article>
  );
}
