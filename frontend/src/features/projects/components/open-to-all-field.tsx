"use client";

import { Field, FieldContent, FieldDescription, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { messages } from "@/messages";

const m = messages.projects;

/**
 * Who can enter a project: only the people added to it, or every member of the workspace as an
 * editor (D-192). Shared by the new-project dialog and the project settings.
 */
export function OpenToAllField({
  idPrefix,
  value,
  onChange,
  disabled,
}: {
  /** Keeps the ids of the two options apart when more than one of these is on a page. */
  idPrefix: string;
  value: boolean;
  onChange: (open: boolean) => void;
  disabled?: boolean;
}) {
  const options = [
    { value: "members", label: m.accessMembers, hint: m.accessMembersHint },
    { value: "open", label: m.accessOpen, hint: m.accessOpenHint },
  ];
  return (
    <FieldSet>
      <FieldLegend variant="label">{m.accessLabel}</FieldLegend>
      <RadioGroup
        value={value ? "open" : "members"}
        disabled={disabled}
        onValueChange={(next) => onChange(next === "open")}
      >
        {options.map((option) => (
          <Field key={option.value} orientation="horizontal" data-disabled={disabled ? true : undefined}>
            <RadioGroupItem id={`${idPrefix}-${option.value}`} value={option.value} />
            <FieldContent>
              <FieldLabel htmlFor={`${idPrefix}-${option.value}`} className="font-normal">
                {option.label}
              </FieldLabel>
              <FieldDescription>{option.hint}</FieldDescription>
            </FieldContent>
          </Field>
        ))}
      </RadioGroup>
    </FieldSet>
  );
}
