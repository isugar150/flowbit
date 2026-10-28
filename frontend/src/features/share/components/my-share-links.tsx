"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { CheckIcon, CopyIcon, Trash2Icon } from "lucide-react";
import { useInBrowser } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { copyText } from "@/lib/copy-text";
import { messages } from "@/messages";
import { revokeShare } from "../api";
import { shareUrl } from "../links";
import type { ShareSummary } from "../types";

const m = messages.share;
const monthLabel = (month: string) => messages.issues.views.monthTitle(Number(month.slice(0, 4)), Number(month.slice(5, 7)));

/** What a link is about, in a few words: "2026년 10월 캘린더 · 담당 티켓", "티켓 · FB-12 제목". */
function titleOf(link: ShareSummary): string {
  const { params } = link;
  const label = m.kinds[link.kind].label;
  const scope = params.scope === "created" ? m.page.scope.created : m.page.scope.assigned;
  switch (link.kind) {
    case "MY_ISSUES_CALENDAR":
      return `${monthLabel(params.month ?? "")} ${label} · ${scope}`;
    case "MY_ISSUES_TIMELINE":
      return `${m.range.summary(monthLabel(params.from ?? ""), monthLabel(params.to ?? ""))} ${label} · ${scope}`;
    case "ISSUE":
      return `${label} · ${link.subject ?? m.mine.noIssue(params.number ?? "")}`;
  }
}

/** One link: what it is, its address to copy, and the delete. */
function ShareLinkRow({ link, onDeleted }: { link: ShareSummary; onDeleted: () => void }) {
  const inBrowser = useInBrowser();
  const [copied, setCopied] = useState<"copied" | "failed" | null>(null);
  const [confirming, setConfirming] = useState(false);
  const remove = useMutation({ mutationFn: () => revokeShare(link.id), onSuccess: onDeleted });
  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(null), 2500);
    return () => clearTimeout(timer);
  }, [copied]);
  const url = inBrowser ? shareUrl(window.location.origin, link.path) : link.path;
  const title = titleOf(link);

  return (
    <li className="flex flex-col gap-3 px-4 py-3.5">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="truncate text-[0.875rem] leading-5 font-medium" title={title}>
          {title}
        </span>
        <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">
          {m.mine.created(link.createdAt.slice(0, 10))}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Input
          readOnly
          value={url}
          aria-label={m.mine.linkOf(title)}
          onFocus={(event) => event.currentTarget.select()}
          className="h-8 min-w-[min(12rem,100%)] flex-1 text-[0.8125rem] max-md:h-11"
        />
        <Button
          type="button"
          variant="outline"
          className="h-8 max-md:h-11"
          onClick={async () => setCopied((await copyText(url)) ? "copied" : "failed")}
        >
          {copied === "copied" ? <CheckIcon data-icon="inline-start" /> : <CopyIcon data-icon="inline-start" />}
          {copied === "copied" ? m.copied : m.copy}
        </Button>
        <Button
          type="button"
          variant={confirming ? "destructive" : "outline"}
          className="h-8 max-md:h-11"
          disabled={remove.isPending}
          onClick={() => (confirming ? remove.mutate() : setConfirming(true))}
          onBlur={() => setConfirming(false)}
        >
          {remove.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : <Trash2Icon data-icon="inline-start" />}
          {remove.isPending ? m.mine.deleting : confirming ? m.mine.deleteConfirm : m.mine.delete}
        </Button>
      </div>
      {copied === "failed" ? (
        <p role="status" className="text-xs leading-4 text-destructive">
          {m.copyFailed}
        </p>
      ) : null}
      {remove.isError ? <FormAlert>{apiErrorMessage(remove.error)}</FormAlert> : null}
    </li>
  );
}

/**
 * A page of the share links the person has made and not stopped (D-202): what each one is, its address to copy, and the delete (which stops it: it opens no more). Which links, and the way
 * to the next page, are the page's (D-204).
 */
export function MyShareLinks({ links }: { links: ShareSummary[] }) {
  const router = useRouter();
  // A link that was deleted leaves the list at once, before the page has been read again.
  const [deleted, setDeleted] = useState<ReadonlySet<string>>(new Set());
  const shown = links.filter((link) => !deleted.has(link.id));
  if (shown.length === 0) return null;
  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-col divide-y rounded-xl border">
        {shown.map((link) => (
          <ShareLinkRow
            key={link.id}
            link={link}
            onDeleted={() => {
              setDeleted((current) => new Set(current).add(link.id));
              router.refresh();
            }}
          />
        ))}
      </ul>
      <p className="text-xs leading-4 text-muted-foreground">{m.mine.deleteHint}</p>
    </div>
  );
}
