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
import { memberDailySummary, saveMemberDailySummary, sendMemberDailySummary, type MemberDailySummary } from "../api";

const m = messages.members.dailySummary;

/** A setting on one line: what it is on the left, the control on the right (as in the member's other sections). */
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

/**
 * The daily summary for one member, set by an administrator (D-216): on or off (off until switched on), the time of
 * day in the member's own time zone, when it last went out, and a try that sends it at once. Each choice is saved as it
 * is made; the time when the field is left.
 */
export function MemberDailySummarySettings({ memberId, memberName }: { memberId: string; memberName: string }) {
  const queryClient = useQueryClient();
  const queryKey = ["member-daily-summary", memberId];
  const summary = useQuery({ queryKey, queryFn: () => memberDailySummary(memberId), staleTime: 0 });
  // The time as it is being typed; null when it is the saved one that shows.
  const [typed, setTyped] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: (changes: { enabled: boolean; time: string }) => saveMemberDailySummary({ memberId, ...changes }),
    onSuccess: (saved) => {
      queryClient.setQueryData<MemberDailySummary>(queryKey, saved);
      setTyped(null);
    },
  });
  const send = useMutation({ mutationFn: () => sendMemberDailySummary(memberId) });

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
          aria-label={m.timeOf(memberName)}
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
              {send.data.sent ? m.sent : m.nothingToSend}
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
