"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { FieldDescription, FieldError } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Spinner } from "@/components/ui/spinner";
import { SettingsActions } from "@/components/settings-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { saveRetention } from "../api";
import type { RetentionItem, RetentionPeriod, RetentionSettings, RetentionUnit } from "../types";

const m = messages.retention;

const ITEMS: RetentionItem[] = ["issues", "documents", "comments", "shareAccesses"];
const UNITS: RetentionUnit[] = ["DAYS", "WEEKS", "MONTHS"];
const MAX_AMOUNT = 999;

/** One row of the form. The amount stays text while it is typed; it is checked on save. */
interface Row {
  limited: boolean;
  amount: string;
  unit: RetentionUnit;
}

type Form = Record<RetentionItem, Row>;

function rowOf(period: RetentionPeriod | null): Row {
  return period
    ? { limited: true, amount: String(period.amount), unit: period.unit }
    : { limited: false, amount: "30", unit: "DAYS" };
}

function formOf(settings: RetentionSettings): Form {
  return {
    issues: rowOf(settings.issues),
    documents: rowOf(settings.documents),
    comments: rowOf(settings.comments),
    shareAccesses: rowOf(settings.shareAccesses),
  };
}

function amountOf(row: Row): number | null {
  if (!/^\d{1,3}$/.test(row.amount.trim())) return null;
  const amount = Number(row.amount);
  return amount >= 1 && amount <= MAX_AMOUNT ? amount : null;
}

/** Roughly how many days a period is, only to tell whether a save removes more than before. */
function daysOf(period: RetentionPeriod): number {
  return period.amount * { DAYS: 1, WEEKS: 7, MONTHS: 30 }[period.unit];
}

/** Whether saving `next` removes something sooner than `saved` would. */
function removesMore(saved: RetentionSettings, next: RetentionSettings): boolean {
  return ITEMS.some((item) => {
    const after = next[item];
    const before = saved[item];
    return after !== null && (before === null || daysOf(after) < daysOf(before));
  });
}

/**
 * For each kind of deleted thing: keep it for good, or remove it for good some days, weeks or
 * months after it was deleted. A save that removes more than before asks once more.
 */
export function RetentionSettingsForm({ settings }: { settings: RetentionSettings }) {
  const router = useRouter();
  const [saved, setSaved] = useState(settings);
  const [form, setForm] = useState<Form>(() => formOf(settings));
  const [invalid, setInvalid] = useState<RetentionItem[]>([]);
  const [confirming, setConfirming] = useState(false);
  const save = useMutation({
    mutationFn: saveRetention,
    onSuccess: (next) => {
      setSaved(next);
      setForm(formOf(next));
      router.refresh();
    },
    onSettled: () => setConfirming(false),
  });
  const serverErrors = fieldErrorsOf(save.error);
  const generalError = save.error && Object.keys(serverErrors).length === 0 ? save.error : null;

  const set = (item: RetentionItem, change: Partial<Row>) => {
    setForm((current) => ({ ...current, [item]: { ...current[item], ...change } }));
    setInvalid((current) => current.filter((other) => other !== item));
    setConfirming(false);
    save.reset();
  };

  return (
    <form
      noValidate
      className="flex flex-col gap-6"
      onSubmit={(event) => {
        event.preventDefault();
        const wrong = ITEMS.filter((item) => form[item].limited && amountOf(form[item]) === null);
        if (wrong.length > 0) {
          setInvalid(wrong);
          return;
        }
        const next = Object.fromEntries(
          ITEMS.map((item) => {
            const row = form[item];
            const amount = amountOf(row);
            return [item, row.limited && amount !== null ? { amount, unit: row.unit } : null];
          }),
        ) as RetentionSettings;
        if (!confirming && removesMore(saved, next)) {
          save.reset();
          setConfirming(true);
          return;
        }
        save.mutate(next);
      }}
    >
      <p className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5 text-muted-foreground">{m.notice}</p>
      {generalError ? <FormAlert>{apiErrorMessage(generalError)}</FormAlert> : null}
      <ul className="flex flex-col divide-y rounded-xl border">
        {ITEMS.map((item) => {
          const row = form[item];
          const text = m.items[item];
          const error = invalid.includes(item) ? m.amountInvalid : serverErrors[`${item}.amount`];
          const noteId = `retention-${item}-note`;
          return (
            <li key={item} className="flex flex-col gap-3 px-4 py-3.5 sm:flex-row sm:items-start sm:justify-between">
              <div className="flex min-w-0 flex-col gap-0.5">
                <span className="text-[0.875rem] leading-5 font-medium">{text.label}</span>
                <span id={noteId} className="text-[0.8125rem] leading-5 text-muted-foreground">
                  {text.hint}
                </span>
              </div>
              <div className="flex shrink-0 flex-col gap-1.5 sm:items-end">
                <div className="flex flex-wrap items-center gap-2">
                  <NativeSelect
                    aria-label={m.modeLabel(text.label)}
                    aria-describedby={noteId}
                    value={row.limited ? "limited" : "keep"}
                    className="max-md:[&_select]:h-11"
                    onChange={(event) => set(item, { limited: event.target.value === "limited" })}
                  >
                    <NativeSelectOption value="keep">{m.keep}</NativeSelectOption>
                    <NativeSelectOption value="limited">{m.limited}</NativeSelectOption>
                  </NativeSelect>
                  {row.limited ? (
                    <>
                      <Input
                        type="text"
                        inputMode="numeric"
                        maxLength={3}
                        aria-label={m.amountLabel(text.label)}
                        aria-invalid={error ? true : undefined}
                        aria-describedby={error ? `retention-${item}-error` : undefined}
                        value={row.amount}
                        className="h-8 w-16 text-right tabular-nums max-md:h-11"
                        onChange={(event) => set(item, { amount: event.target.value })}
                      />
                      <NativeSelect
                        aria-label={m.unitLabel(text.label)}
                        value={row.unit}
                        className="max-md:[&_select]:h-11"
                        onChange={(event) => set(item, { unit: event.target.value as RetentionUnit })}
                      >
                        {UNITS.map((unit) => (
                          <NativeSelectOption key={unit} value={unit}>
                            {m.units[unit]}
                          </NativeSelectOption>
                        ))}
                      </NativeSelect>
                      <span className="text-[0.8125rem] leading-5 text-muted-foreground">{m.after}</span>
                    </>
                  ) : null}
                </div>
                {error ? <FieldError id={`retention-${item}-error`}>{error}</FieldError> : null}
              </div>
            </li>
          );
        })}
      </ul>
      <FieldDescription>{m.schedule}</FieldDescription>
      {confirming ? (
        <p role="status" className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5">
          {m.confirm}
        </p>
      ) : null}
      <SettingsActions>
        <Button
          type="submit"
          variant={confirming ? "destructive" : "default"}
          className="h-9 max-md:h-11"
          disabled={save.isPending}
        >
          {save.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
          {save.isPending ? m.saving : confirming ? m.confirmSave : m.save}
        </Button>
        {confirming && !save.isPending ? (
          <Button type="button" variant="outline" className="h-9 max-md:h-11" onClick={() => setConfirming(false)}>
            {m.cancel}
          </Button>
        ) : null}
        {save.isSuccess ? <FieldDescription role="status">{m.saved}</FieldDescription> : null}
      </SettingsActions>
    </form>
  );
}
