"use client";

import { useState } from "react";
import { PlusIcon, TagIcon, UserIcon, XIcon } from "lucide-react";
import { MultiCombobox } from "@/components/multi-combobox";
import { PropertyRow } from "@/components/property-card";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { FormAlert } from "@/features/auth/components/form-alert";
import { ChipSelect, chipClass } from "@/features/issues/components/chip-select";
import { DateRangeChip } from "@/features/issues/components/date-chips";
import { PersonAvatar, personName } from "@/components/person";
import { NONE, priorityOptions } from "@/features/issues/options";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { updateProject } from "../api";
import { projectStatusOptions } from "../meta";
import type { Project, ProjectChanges, ProjectMember } from "../types";

const m = messages.projects;
const LABELS_MAX = 10;
const LABEL_MAX_LENGTH = 30;

/**
 * Status, priority, lead, dates and labels of a project, changed in place in the "속성" card
 * of the overview. Anyone who may edit the project can change them.
 */
export function ProjectProperties({
  project,
  members,
  labelSuggestions,
  canEdit,
}: {
  project: Project;
  members: ProjectMember[];
  /** Labels already used on other projects, offered while typing a new one. */
  labelSuggestions: string[];
  canEdit: boolean;
}) {
  const mutation = useRefreshingMutation(updateProject);
  const change = (changes: ProjectChanges) => mutation.mutate({ id: project.id, changes });
  const locked = !canEdit || mutation.isPending;

  const leadOptions = [
    { value: NONE, label: m.noLead, icon: <UserIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...members.map((member) => ({
      value: member.userId,
      label: personName(member),
      icon: <PersonAvatar person={member} />,
    })),
  ];
  // A lead who has since left the project still shows by name until someone picks another.
  if (project.lead && !members.some((member) => member.userId === project.lead?.id)) {
    leadOptions.push({
      value: project.lead.id,
      label: personName(project.lead),
      icon: <PersonAvatar person={project.lead} />,
    });
  }

  return (
    <>
      {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
      <PropertyRow label={m.propStatus}>
        <ChipSelect
          label={m.propStatus}
          value={project.status}
          options={projectStatusOptions}
          disabled={locked}
          onChange={(status) => change({ status })}
        />
      </PropertyRow>
      <PropertyRow label={m.propPriority}>
        <ChipSelect
          label={m.propPriority}
          value={project.priority}
          options={priorityOptions}
          disabled={locked}
          onChange={(priority) => change({ priority })}
        />
      </PropertyRow>
      <PropertyRow label={m.propLead}>
        <ChipSelect
          label={m.propLead}
          value={project.lead?.id ?? NONE}
          options={leadOptions}
          disabled={locked}
          onChange={(value) => change({ leadId: value === NONE ? null : value })}
        />
      </PropertyRow>
      <PropertyRow label={m.propDates}>
        <DateRangeChip
          label={m.propDates}
          placeholder={m.noDates}
          start={project.startDate}
          end={project.targetDate}
          disabled={locked}
          onChange={({ start, end }) => change({ startDate: start, targetDate: end })}
        />
      </PropertyRow>
      <PropertyRow label={m.propLabels}>
        <LabelsEditor
          labels={project.labels}
          suggestions={labelSuggestions}
          disabled={locked}
          onChange={(labels) => change({ labels })}
        />
      </PropertyRow>
    </>
  );
}

/**
 * The labels as chips with a remove button each, and a "+" that opens a combobox: tick labels
 * used on other projects, or type a new one.
 */
function LabelsEditor({
  labels,
  suggestions,
  disabled,
  onChange,
}: {
  labels: string[];
  suggestions: string[];
  disabled: boolean;
  onChange: (labels: string[]) => void;
}) {
  const [tooMany, setTooMany] = useState(false);
  const has = (label: string) => labels.some((existing) => existing.toLowerCase() === label.toLowerCase());
  // A label is its own name here: there is no label table for projects.
  const options = [...labels, ...suggestions.filter((label) => !has(label))].map((label) => ({
    id: label,
    label,
    icon: <TagIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />,
  }));
  const change = (next: string[]) => {
    setTooMany(next.length > LABELS_MAX);
    if (next.length <= LABELS_MAX) onChange(next);
  };

  return (
    <div className="flex min-w-0 flex-wrap items-center gap-1">
      {labels.map((label) => (
        <span key={label} className={cn(chipClass, "gap-1 pr-1.5")}>
          <TagIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span className="truncate">{label}</span>
          {disabled ? null : (
            <button
              type="button"
              aria-label={m.removeLabel(label)}
              onClick={() => onChange(labels.filter((existing) => existing !== label))}
              className="rounded-full p-0.5 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <XIcon className="size-3" aria-hidden="true" />
            </button>
          )}
        </span>
      ))}
      {disabled ? null : (
        <Popover onOpenChange={() => setTooMany(false)}>
          <PopoverTrigger
            render={
              <button
                type="button"
                aria-label={m.addLabel}
                title={m.addLabel}
                className={cn(chipClass, labels.length > 0 && "size-7 justify-center px-0 max-md:size-9")}
              />
            }
          >
            <PlusIcon className="size-3.5 text-muted-foreground" aria-hidden="true" />
            {labels.length > 0 ? null : <span className="text-muted-foreground">{m.addLabel}</span>}
          </PopoverTrigger>
          <PopoverContent className="w-60 p-0">
            <MultiCombobox
              options={options}
              value={labels}
              onChange={change}
              onCreate={(name) => change([...labels, name])}
              placeholder={m.labelSearchPlaceholder}
              listLabel={m.propLabels}
              heading={m.labelsHeading}
              emptyText={m.noLabelSuggestions}
              createText={m.createLabel}
              error={tooMany ? m.tooManyLabels : null}
              maxLength={LABEL_MAX_LENGTH}
            />
          </PopoverContent>
        </Popover>
      )}
    </div>
  );
}
