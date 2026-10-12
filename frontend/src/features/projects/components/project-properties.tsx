"use client";

import { useId, useState } from "react";
import { ArrowRightIcon, PlusIcon, TagIcon, UserIcon, XIcon } from "lucide-react";
import { PropertyRow } from "@/components/property-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import { ChipSelect, chipClass } from "@/features/issues/components/chip-select";
import { DueDateChip } from "@/features/issues/components/due-date-chip";
import { NONE, PersonAvatar, priorityOptions } from "@/features/issues/options";
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
  const change = (changes: ProjectChanges) => mutation.mutate({ key: project.key, changes });
  const locked = !canEdit || mutation.isPending;

  const leadOptions = [
    { value: NONE, label: m.noLead, icon: <UserIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...members.map((member) => ({
      value: member.userId,
      label: member.displayName ?? member.loginId,
      icon: <PersonAvatar person={member} />,
    })),
  ];
  // A lead who has since left the project still shows by name until someone picks another.
  if (project.lead && !members.some((member) => member.userId === project.lead?.id)) {
    leadOptions.push({
      value: project.lead.id,
      label: project.lead.displayName ?? project.lead.loginId,
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
        <div className="flex min-w-0 flex-wrap items-center gap-1">
          <DueDateChip
            value={project.startDate}
            label={m.startDate}
            clearLabel={m.clearStartDate}
            max={project.targetDate ?? undefined}
            disabled={locked}
            onChange={(startDate) => change({ startDate })}
          />
          <ArrowRightIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <DueDateChip
            value={project.targetDate}
            label={m.targetDate}
            clearLabel={m.clearTargetDate}
            min={project.startDate ?? undefined}
            disabled={locked}
            onChange={(targetDate) => change({ targetDate })}
          />
        </div>
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

/** The labels as chips with a remove button each, and a "+" that opens a field with suggestions. */
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
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState("");
  const listId = useId();
  const has = (label: string) => labels.some((existing) => existing.toLowerCase() === label.toLowerCase());
  const offered = suggestions.filter((label) => !has(label));
  const full = labels.length >= LABELS_MAX;

  const add = () => {
    const label = draft.trim();
    setDraft("");
    setAdding(false);
    if (label && !has(label) && !full) onChange([...labels, label]);
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
      {disabled ? null : adding ? (
        <span className={cn(chipClass, "max-w-40 focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50")}>
          <input
            // The field only appears after pressing "+", so taking focus here is what the person expects.
            autoFocus
            list={listId}
            value={draft}
            maxLength={LABEL_MAX_LENGTH}
            placeholder={m.newLabelPlaceholder}
            aria-label={m.newLabelLabel}
            autoComplete="off"
            onChange={(event) => setDraft(event.target.value)}
            onBlur={add}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                add();
              } else if (event.key === "Escape") {
                setDraft("");
                setAdding(false);
              }
            }}
            className="w-full min-w-0 bg-transparent outline-none placeholder:text-muted-foreground"
          />
          <datalist id={listId}>
            {offered.map((label) => (
              <option key={label} value={label} />
            ))}
          </datalist>
        </span>
      ) : (
        <button
          type="button"
          aria-label={m.addLabel}
          title={full ? m.tooManyLabels : m.addLabel}
          disabled={full}
          onClick={() => setAdding(true)}
          className={cn(chipClass, labels.length > 0 && "size-7 justify-center px-0 max-md:size-9")}
        >
          <PlusIcon className="size-3.5 text-muted-foreground" aria-hidden="true" />
          {labels.length > 0 ? null : <span className="text-muted-foreground">{m.addLabel}</span>}
        </button>
      )}
    </div>
  );
}
