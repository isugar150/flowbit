"use client";

import { useId, useState } from "react";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { messages } from "@/messages";
import { monthOfDate, monthsIn, pickableMonths, rangeAfter, type MonthRange } from "../month-range";

const m = messages.share.range;
const monthTitle = messages.issues.views.monthTitle;

const labelOf = (month: string) => monthTitle(Number(month.slice(0, 4)), Number(month.slice(5, 7)));

/**
 * Two selects, the month a range starts in and the month it ends in (that one included). Moving one end
 * past the other, or so far that the range would be too long to share, takes the other end along
 * (`rangeAfter`), so what is on the screen can always be shared. Reads the clock for the months on offer, so it is
 * only drawn in the browser (the share window opens on a press).
 */
export function MonthRangeFields({ value, onChange }: { value: MonthRange; onChange: (next: MonthRange) => void }) {
  const fromId = useId();
  const toId = useId();
  const [options] = useState(() => pickableMonths(monthOfDate(new Date())));
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1.5">
          <label htmlFor={fromId} className="text-[0.8125rem] leading-5 font-medium">
            {m.from}
          </label>
          <NativeSelect id={fromId} value={value.from} className="max-md:[&_select]:h-11" onChange={(event) => onChange(rangeAfter(value, "from", event.target.value))}>
            {options.from.map((month) => (
              <NativeSelectOption key={month} value={month}>
                {labelOf(month)}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
        <span aria-hidden="true" className="pb-1.5 text-muted-foreground">
          ~
        </span>
        <div className="flex flex-col gap-1.5">
          <label htmlFor={toId} className="text-[0.8125rem] leading-5 font-medium">
            {m.to}
          </label>
          <NativeSelect id={toId} value={value.to} className="max-md:[&_select]:h-11" onChange={(event) => onChange(rangeAfter(value, "to", event.target.value))}>
            {options.to.map((month) => (
              <NativeSelectOption key={month} value={month}>
                {labelOf(month)}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
      </div>
      <p className="text-xs leading-4 text-muted-foreground">{m.hint(monthsIn(value))}</p>
    </div>
  );
}
