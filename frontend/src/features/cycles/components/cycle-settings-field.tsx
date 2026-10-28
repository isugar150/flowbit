"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useMutation } from "@tanstack/react-query";
import { Field, FieldContent, FieldDescription, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { FormAlert } from "@/features/auth/components/form-alert";
import { useProjectApiScope } from "@/features/projects/project-api-scope";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { getCycles, saveCycleSettings } from "../api";
import type { CycleSettings } from "../types";

const m = messages.cycles;

/** Whether the project runs in cycles, how long one lasts and on which weekday it starts (managers only). Saved at once. */
export function CycleSettingsField({ projectId }: { projectId: string }) {
  const client = useQueryClient();
  const scope = useProjectApiScope();
  const key = ["cycles", projectId];
  const cycles = useQuery({ queryKey: key, queryFn: () => getCycles(projectId, scope) });
  const save = useMutation({
    mutationFn: (settings: CycleSettings) => saveCycleSettings({ projectId, settings, scope }),
    onSuccess: (next) => client.setQueryData(key, next),
  });
  const settings = save.isPending && save.variables ? save.variables : cycles.data?.settings;
  if (!settings) return <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.loading}</p>;

  const change = (changes: Partial<CycleSettings>) => {
    save.reset();
    save.mutate({ ...settings, ...changes });
  };
  const options = [
    { value: "off", label: m.off, hint: m.offHint },
    { value: "on", label: m.on, hint: m.onHint },
  ];

  return (
    <div className="flex flex-col gap-4">
      {save.isError ? <FormAlert>{apiErrorMessage(save.error)}</FormAlert> : null}
      <FieldSet>
        <FieldLegend variant="label">{m.label}</FieldLegend>
        <RadioGroup value={settings.enabled ? "on" : "off"} onValueChange={(next) => change({ enabled: next === "on" })}>
          {options.map((option) => (
            <Field key={option.value} orientation="horizontal">
              <RadioGroupItem id={`project-cycles-${option.value}`} value={option.value} />
              <FieldContent>
                <FieldLabel htmlFor={`project-cycles-${option.value}`} className="font-normal">
                  {option.label}
                </FieldLabel>
                <FieldDescription>{option.hint}</FieldDescription>
              </FieldContent>
            </Field>
          ))}
        </RadioGroup>
      </FieldSet>
      {settings.enabled ? (
        <div className="flex flex-wrap items-end gap-4">
          <label className="flex flex-col gap-1 text-[0.8125rem] leading-5 text-muted-foreground">
            {m.weeksLabel}
            <NativeSelect value={String(settings.weeks)} onChange={(event) => change({ weeks: Number(event.target.value) })}>
              {[1, 2, 3, 4].map((weeks) => (
                <NativeSelectOption key={weeks} value={weeks}>
                  {m.weeks(weeks)}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </label>
          <label className="flex flex-col gap-1 text-[0.8125rem] leading-5 text-muted-foreground">
            {m.startDayLabel}
            <NativeSelect value={String(settings.startDay)} onChange={(event) => change({ startDay: Number(event.target.value) })}>
              {m.weekdays.map((name, index) => (
                <NativeSelectOption key={name} value={index + 1}>
                  {name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </label>
          <p className="basis-full text-xs leading-4 text-muted-foreground">{m.changeNote}</p>
        </div>
      ) : null}
    </div>
  );
}
