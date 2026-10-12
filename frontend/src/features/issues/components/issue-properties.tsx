"use client";

import { PropertyRow } from "@/components/property-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { NONE, assigneeOptions, milestoneOptions, presetOptions, priorityOptions, statusOptions } from "../options";
import type { Issue, IssueChanges } from "../types";
import { ChipSelect } from "./chip-select";
import { DateChip } from "./date-chips";

const m = messages.issues;

/** The editable properties of one issue, shown in the card on the right of the issue page. */
export function IssueProperties({
  projectKey,
  issue,
  members,
  milestones,
  canEdit,
}: {
  projectKey: string;
  issue: Issue;
  members: ProjectMember[];
  milestones: Milestone[];
  canEdit: boolean;
}) {
  const mutation = useRefreshingMutation(updateIssue);
  const change = (changes: IssueChanges) => mutation.mutate({ projectKey, number: issue.number, changes });
  const locked = !canEdit || mutation.isPending;

  return (
    <>
      {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
      <dl className="flex flex-col">
        <PropertyRow label={m.status}>
          <ChipSelect
            label={m.status}
            value={issue.status}
            options={statusOptions}
            disabled={locked}
            onChange={(status) => change({ status })}
          />
        </PropertyRow>
        <PropertyRow label={m.priority}>
          <ChipSelect
            label={m.priority}
            value={issue.priority}
            options={priorityOptions}
            disabled={locked}
            onChange={(priority) => change({ priority })}
          />
        </PropertyRow>
        <PropertyRow label={m.assignee}>
          <ChipSelect
            label={m.assignee}
            value={issue.assignee?.id ?? NONE}
            options={assigneeOptions(members)}
            disabled={locked}
            onChange={(value) => change({ assigneeId: value === NONE ? null : value })}
          />
        </PropertyRow>
        <PropertyRow label={m.milestone}>
          <ChipSelect
            label={m.milestone}
            value={issue.milestone?.id ?? NONE}
            options={milestoneOptions(milestones)}
            disabled={locked}
            onChange={(value) => change({ milestoneId: value === NONE ? null : value })}
          />
        </PropertyRow>
        <PropertyRow label={m.preset}>
          <ChipSelect
            label={m.preset}
            value={issue.preset ?? NONE}
            options={presetOptions}
            disabled={locked}
            onChange={(value) => change({ preset: value === NONE ? null : value })}
          />
        </PropertyRow>
        <PropertyRow label={m.dueDate}>
          <DateChip
            label={m.dueDate}
            placeholder={m.noDueDate}
            value={issue.dueDate}
            disabled={locked}
            onChange={(dueDate) => change({ dueDate })}
          />
        </PropertyRow>
      </dl>
    </>
  );
}
