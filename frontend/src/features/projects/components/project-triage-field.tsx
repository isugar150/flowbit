"use client";

import { Field, FieldContent, FieldDescription, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { useProjectApi } from "../project-api-scope";

const m = messages.projects;

/**
 * Whether what people who do not manage the project make waits in the triage queue first (D-231), in its settings (managers
 * only). Off to begin with. The pick is saved at once, and a pick the server refuses goes back by itself.
 */
export function ProjectTriageField({ projectId, enabled }: { projectId: string; enabled: boolean }) {
  const { updateProject } = useProjectApi();
  const change = useOptimisticMutation(
    enabled,
    (_current, next: { id: string; enabled: boolean }) => next.enabled,
    (next) => updateProject({ id: next.id, changes: { triageEnabled: next.enabled } }),
  );
  const options = [
    { value: "off", label: m.triageOff, hint: m.triageOffHint },
    { value: "on", label: m.triageOn, hint: m.triageOnHint },
  ];

  return (
    <div className="flex flex-col gap-3">
      {change.isError ? <FormAlert>{apiErrorMessage(change.error)}</FormAlert> : null}
      <FieldSet>
        <FieldLegend variant="label">{m.triageLabel}</FieldLegend>
        <RadioGroup
          value={change.value ? "on" : "off"}
          onValueChange={(next) => {
            change.reset();
            change.mutate({ id: projectId, enabled: next === "on" });
          }}
        >
          {options.map((option) => (
            <Field key={option.value} orientation="horizontal">
              <RadioGroupItem id={`project-triage-${option.value}`} value={option.value} />
              <FieldContent>
                <FieldLabel htmlFor={`project-triage-${option.value}`} className="font-normal">
                  {option.label}
                </FieldLabel>
                <FieldDescription>{option.hint}</FieldDescription>
              </FieldContent>
            </Field>
          ))}
        </RadioGroup>
      </FieldSet>
    </div>
  );
}
