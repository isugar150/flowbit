"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { dailySummaryOf, saveDailySummary, sendDailySummary, type DailySummary, type DailySummarySent, type DailySummaryTarget } from "../api";

const m = messages.dailySummary;

/** A setting on one line: what it is on the left, the control on the right (as in the other settings sections). */
function Row({ title, description, children }: { title: string; description?: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
      <div className="flex min-w-0 flex-1 basis-56 flex-col gap-0.5">
        <span className="text-[0.8125rem] leading-5 font-medium">{title}</span>
        {description ? <span className="text-[0.8125rem] leading-5 text-muted-foreground">{description}</span> : null}
      </div>
      {children}
    </div>
  );
}

/** What came of sending it now, in words: where it arrived, or why it did not go anywhere (D-220). */
function sentText(result: DailySummarySent): string {
  if (!result.hadContent) return m.nothingToSend;
  const ways = [
    result.mailed > 0 ? m.viaMail(result.mailed) : null,
    result.pushed > 0 ? m.viaPush(result.pushed) : null,
    result.apiReached > 0 ? m.viaApi(result.apiReached) : null,
  ].filter((way): way is string => way !== null);
  const failed = result.apiFailed > 0 ? m.apiFailed(result.apiFailed) : null;
  if (ways.length === 0) return failed ?? m.nowhere;
  return [m.sentVia(ways.join(", ")), failed].filter(Boolean).join(" · ");
}

/**
 * The daily summary of one person (D-216, D-221): on or off (off until someone switches it on), the time of day in their
 * own time zone, when it last went out, and a button that sends it at once. The same setting is changed by the person under
 * 내 설정 > 알림 (`target` "me") and by an administrator under 관리자 설정 > 구성원 (`target` "member"). Each choice is saved
 * as it is made; the time when the field is left.
 */
export function DailySummarySettings({ target, name }: { target: DailySummaryTarget; name?: string }) {
  const queryClient = useQueryClient();
  const queryKey = ["daily-summary", target.kind, target.kind === "member" ? target.id : "me"];
  const summary = useQuery({ queryKey, queryFn: () => dailySummaryOf(target), staleTime: 0 });
  // The time as it is being typed; null when it is the saved one that shows.
  const [typed, setTyped] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: (changes: { enabled: boolean; time: string }) => saveDailySummary(target, changes),
    onSuccess: (saved) => {
      queryClient.setQueryData<DailySummary>(queryKey, saved);
      setTyped(null);
    },
  });
  const send = useMutation({ mutationFn: () => sendDailySummary(target) });

  if (summary.isPending) {
    return (
      <div className="flex flex-col gap-2" aria-busy="true">
        <span className="sr-only">{m.loading}</span>
        <Skeleton className="h-8" />
        <Skeleton className="h-8" />
      </div>
    );
  }
  if (summary.isError) return <FormAlert>{m.failed}</FormAlert>;
  const current = summary.data;
  const error = save.isError ? save.error : send.isError ? send.error : null;

  return (
    <div className="flex flex-col gap-3">
      {error ? <FormAlert>{apiErrorMessage(error)}</FormAlert> : null}
      <Row title={m.enabled} description={m.enabledHint}>
        <NativeSelect
          size="sm"
          className="w-28 max-md:h-11"
          aria-label={m.enabled}
          value={current.enabled ? "on" : "off"}
          disabled={save.isPending}
          onChange={(event) => save.mutate({ enabled: event.target.value === "on", time: current.time })}
        >
          <NativeSelectOption value="off">{m.off}</NativeSelectOption>
          <NativeSelectOption value="on">{m.on}</NativeSelectOption>
        </NativeSelect>
      </Row>
      <Row title={m.time} description={m.timeHint(current.timezone)}>
        <Input
          type="time"
          step={60}
          className="w-28 max-md:h-11"
          aria-label={target.kind === "member" ? m.timeOf(name ?? "") : m.time}
          value={typed ?? current.time}
          onChange={(event) => setTyped(event.target.value)}
          onBlur={() => {
            if (typed && typed !== current.time) save.mutate({ enabled: current.enabled, time: typed });
            else setTyped(null);
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" && typed && typed !== current.time) save.mutate({ enabled: current.enabled, time: typed });
          }}
        />
      </Row>
      <Row title={m.lastSent}>
        <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{current.lastSentOn ?? m.never}</span>
      </Row>
      <Row title={m.sendNow} description={m.sendNowHint}>
        <div className="flex items-center gap-2">
          {send.isSuccess ? (
            <span role="status" className="text-[0.8125rem] leading-5 text-muted-foreground">
              {sentText(send.data)}
            </span>
          ) : null}
          <Button type="button" variant="outline" size="sm" className="max-md:h-11" disabled={send.isPending} onClick={() => send.mutate()}>
            {send.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
            {m.sendNow}
          </Button>
        </div>
      </Row>
    </div>
  );
}
