"use client";

import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { CheckIcon, CopyIcon, Share2Icon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { copyText } from "@/lib/copy-text";
import { messages } from "@/messages";
import { createShare, fetchShareAccesses, revokeShare } from "../api";
import { describeClient, embedCode, shareUrl } from "../links";
import type { ShareAccess, ShareLink, ShareTarget } from "../types";

const m = messages.share;

/**
 * What a page that lets the person choose what to share (the months a timeline covers) adds to the share window. Its
 * fields come first, and the link is made when they press "링크 만들기" instead of when the window opens: each choice
 * is a link of its own, and a link made at every opening would pile up links that nobody can stop.
 */
export type SharePick = {
  /** The fields that choose; `change` sets the parameters. */
  render: (params: Record<string, string>, change: (next: Record<string, string>) => void) => React.ReactNode;
  /** The choice in a few words, once its link is made. */
  summary: (params: Record<string, string>) => string;
};

/**
 * The button that shares a page (docs/features/sharing.md, D-197). Pressing it opens the share
 * window, which makes the link for `target` (or finds the one the person already has: the same
 * page is always the same address). It is the same for every kind of page: what differs is only
 * the target the page hands it and, where there is something to choose, `pick`.
 */
export function ShareButton({ target, pick }: { target: ShareTarget; pick?: SharePick }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button type="button" variant="ghost" size="sm" className="text-muted-foreground max-md:h-11" onClick={() => setOpen(true)}>
        <Share2Icon data-icon="inline-start" />
        {m.button}
      </Button>
      {open ? <ShareDialog target={target} pick={pick} onClose={() => setOpen(false)} /> : null}
    </>
  );
}

/** A value to copy, in a box that selects itself when it is pressed, with a button that copies it. */
function CopyField({ label, hint, value, multiline, within }: { label: string; hint?: string; value: string; multiline?: boolean; within: React.RefObject<HTMLElement | null> }) {
  const id = useId();
  const [result, setResult] = useState<"copied" | "failed" | null>(null);
  useEffect(() => {
    if (!result) return;
    const timer = setTimeout(() => setResult(null), 2500);
    return () => clearTimeout(timer);
  }, [result]);
  const select = (event: React.SyntheticEvent<HTMLInputElement | HTMLTextAreaElement>) => event.currentTarget.select();
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-[0.8125rem] leading-5 font-medium">
        {label}
      </label>
      <div className="flex items-start gap-2">
        {multiline ? (
          <Textarea id={id} readOnly rows={3} value={value} onFocus={select} className="min-h-16 flex-1 font-mono text-xs leading-4" />
        ) : (
          <Input id={id} readOnly value={value} onFocus={select} className="h-8 flex-1 max-md:h-11" />
        )}
        <Button
          type="button"
          variant="outline"
          className="h-8 shrink-0 max-md:h-11"
          onClick={async () => setResult((await copyText(value, within.current)) ? "copied" : "failed")}
        >
          {result === "copied" ? <CheckIcon data-icon="inline-start" /> : <CopyIcon data-icon="inline-start" />}
          {result === "copied" ? m.copied : m.copy}
        </Button>
      </div>
      {hint ? <p className="text-xs leading-4 text-muted-foreground">{hint}</p> : null}
      {result === "failed" ? (
        <p role="status" className="text-xs leading-4 text-destructive">
          {m.copyFailed}
        </p>
      ) : null}
    </div>
  );
}

function hostOf(address: string | null): string | null {
  if (!address) return null;
  try {
    return new URL(address).host;
  } catch {
    return address;
  }
}

function clientLabel(access: ShareAccess): string {
  const { browser, os } = describeClient(access.userAgent);
  return [browser, os].filter(Boolean).join(" · ") || m.unknownClient;
}

/** When the link was opened and from where, newest first (the person's own link only). */
function ShareHistory({ id }: { id: string }) {
  const history = useQuery({ queryKey: ["share-accesses", id], queryFn: () => fetchShareAccesses(id), staleTime: 0 });
  const items = history.data?.items ?? [];
  return (
    <section aria-label={m.historyTitle} className="flex flex-col gap-1.5">
      <h3 className="flex items-baseline justify-between gap-2 text-[0.8125rem] leading-5 font-medium">
        {m.historyTitle}
        {history.data ? <span className="text-xs leading-4 font-normal text-muted-foreground tabular-nums">{m.historyCount(history.data.total, items.length)}</span> : null}
      </h3>
      {history.isPending ? <Spinner aria-hidden="true" /> : null}
      {history.isError ? <FormAlert>{apiErrorMessage(history.error)}</FormAlert> : null}
      {history.data && items.length === 0 ? <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.historyEmpty}</p> : null}
      {items.length > 0 ? (
        <ul className="max-h-48 divide-y overflow-y-auto rounded-lg border px-3">
          {items.map((access) => {
            const page = access.embedded ? hostOf(access.referrer) : null;
            return (
              <li key={`${access.accessedAt}-${access.ipAddress}`} className="flex flex-col gap-0.5 py-1.5 text-[0.8125rem] leading-5">
                <span className="flex items-baseline justify-between gap-2">
                  <span className="truncate" title={access.userAgent ?? undefined}>
                    {clientLabel(access)}
                    {access.item ? <span className="text-muted-foreground"> · {m.historyItem(access.item)}</span> : null}
                  </span>
                  <span className="shrink-0 text-xs leading-4 text-muted-foreground">
                    <TimeAgo iso={access.accessedAt} />
                  </span>
                </span>
                <span className="truncate text-xs leading-4 text-muted-foreground tabular-nums">
                  {[access.ipAddress, access.embedded ? (page ? m.embeddedIn(page) : m.embedded) : null].filter(Boolean).join(" · ")}
                </span>
              </li>
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}

/** The share window: the link and the embed code to copy, who opened it lately, and the stop. */
function ShareDialog({ target, pick, onClose }: { target: ShareTarget; pick?: SharePick; onClose: () => void }) {
  const text = m.kinds[target.kind];
  const picking = pick !== undefined;
  const box = useRef<HTMLDivElement>(null);
  const [params, setParams] = useState(target.params);
  const [link, setLink] = useState<ShareLink | null>(null);
  const [stopped, setStopped] = useState(false);
  const create = useMutation({
    mutationFn: () => createShare({ kind: target.kind, params }),
    onSuccess: (made) => {
      setLink(made);
      setStopped(false);
    },
  });
  const stop = useMutation({
    mutationFn: (id: string) => revokeShare(id),
    onSuccess: () => {
      setLink(null);
      setStopped(true);
    },
  });
  const { mutate: makeLink } = create;
  // Without anything to choose the link is made as the window opens.
  useEffect(() => {
    if (!picking) makeLink();
  }, [makeLink, picking]);
  const url = link ? shareUrl(window.location.origin, link.path) : "";
  const error = create.error ?? stop.error;

  return (
    <Dialog open onOpenChange={(open) => (open ? null : onClose())}>
      <DialogContent className="sm:max-w-lg">
        <DialogTitle>{text.title}</DialogTitle>
        <DialogDescription>{text.description}</DialogDescription>
        <div ref={box} className="flex flex-col gap-4">
          {error ? <FormAlert>{apiErrorMessage(error)}</FormAlert> : null}
          {pick && !link ? pick.render(params, setParams) : null}
          {create.isPending && !link ? (
            <p role="status" className="flex items-center gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
              <Spinner aria-hidden="true" />
              {m.creating}
            </p>
          ) : null}
          {link ? (
            <>
              {pick ? (
                <p className="flex items-center justify-between gap-2 text-[0.8125rem] leading-5">
                  <span className="font-medium tabular-nums">{pick.summary(link.params)}</span>
                  <Button type="button" variant="ghost" size="sm" className="text-muted-foreground max-md:h-11" onClick={() => setLink(null)}>
                    {m.range.change}
                  </Button>
                </p>
              ) : null}
              <CopyField label={m.linkLabel} value={url} within={box} />
              <CopyField label={m.embedLabel} hint={m.embedHint} value={embedCode(url, text.label)} multiline within={box} />
              <ShareHistory id={link.id} />
            </>
          ) : null}
          {stopped ? (
            <p role="status" className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5">
              {m.stopped} {m.shareAgainHint}
            </p>
          ) : null}
        </div>
        <DialogFooter>
          {link ? (
            <Button type="button" variant="outline" className="max-md:h-11" disabled={stop.isPending} onClick={() => stop.mutate(link.id)}>
              {stop.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
              {stop.isPending ? m.stopping : m.stop}
            </Button>
          ) : null}
          {/* With a choice to make the link comes from pressing this; without one, the same page is shared again. */}
          {!link && (picking || stopped) ? (
            <Button type="button" variant={picking ? "default" : "outline"} className="max-md:h-11" disabled={create.isPending} onClick={() => create.mutate()}>
              {picking ? m.range.create : m.shareAgain}
            </Button>
          ) : null}
          <Button type="button" variant={link || (!picking && !stopped) ? "default" : "outline"} className="max-md:h-11" onClick={onClose}>
            {m.close}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
