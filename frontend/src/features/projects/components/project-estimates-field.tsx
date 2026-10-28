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
 * Whether the project's issues carry an estimate (story points), in its settings (managers only). Off to begin with.
 * The pick is saved at once, and a pick the server refuses goes back by itself.
 */
export function ProjectEstimatesField({ projectId, enabled }: { projectId: string; enabled: boolean }) {
  const { updateProject } = useProjectApi();
  const change = useOptimisticMutation(
    enabled,
    (_current, next: { id: string; enabled: boolean }) => next.enabled,
    (next) => updateProject({ id: next.id, changes: { estimatesEnabled: next.enabled } }),
  );
  const options = [
    { value: "off", label: m.estimatesOff, hint: m.estimatesOffHint },
    { value: "on", label: m.estimatesOn, hint: m.estimatesOnHint },
  ];

  return (
    <div className="flex flex-col gap-3">
      {change.isError ? <FormAlert>{apiErrorMessage(change.error)}</FormAlert> : null}
      <FieldSet>
        <FieldLegend variant="label">{m.estimatesLabel}</FieldLegend>
        <RadioGroup
          value={change.value ? "on" : "off"}
          onValueChange={(next) => {
            change.reset();
            change.mutate({ id: projectId, enabled: next === "on" });
          }}
        >
          {options.map((option) => (
            <Field key={option.value} orientation="horizontal">
              <RadioGroupItem id={`project-estimates-${option.value}`} value={option.value} />
              <FieldContent>
                <FieldLabel htmlFor={`project-estimates-${option.value}`} className="font-normal">
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
