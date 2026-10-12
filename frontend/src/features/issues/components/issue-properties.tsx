"use client";

import { BoxIcon, CircleSlashIcon } from "lucide-react";
import { ProjectIcon } from "@/components/project-icon";
import { PropertyRow } from "@/components/property-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { NONE, assigneeOptions, milestoneOptions, priorityOptions, statusOptions } from "../options";
import type { Issue, IssueChanges, Label } from "../types";
import { ChipSelect, type ChipOption } from "./chip-select";
import { DateChip } from "./date-chips";
import { LabelChip } from "./label-chip";

const m = messages.issues;

/** A project to link an issue to, or none. Only projects the person may put issues into are offered. */
export function projectOptions(projects: ProjectSummary[], current?: string | null): ChipOption<string>[] {
  return [
    { value: NONE, label: m.noProject, icon: <CircleSlashIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...projects
      .filter((project) => (project.myRole && project.myRole !== "VIEWER") || project.key === current)
      .map((project) => ({ value: project.key, label: project.name, icon: <ProjectIcon iconUrl={project.iconUrl} /> })),
  ];
}

/** The editable properties of one issue, shown in the card on the right of the issue. */
export function IssueProperties({
  issue,
  members,
  labels,
  canEdit,
}: {
  issue: Issue;
  /** Who the issue can be given to: the members of its project, or everyone when it has none. */
  members: ProjectMember[];
  /** Every label in the workspace. */
  labels: Label[];
  canEdit: boolean;
}) {
  const mutation = useRefreshingMutation(updateIssue);
  const change = (changes: IssueChanges) => mutation.mutate({ number: issue.number, changes });
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
        <PropertyRow label={m.labels}>
          <LabelChip
            labels={labels}
            value={issue.labels.map((label) => label.id)}
            disabled={locked}
            onChange={(labelIds) => change({ labelIds })}
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

/**
 * The project an issue is linked to and, under it, the milestone inside that project. The issue
 * is its own thing: this only links it, so the project can be changed or removed at any time
 * (the milestone goes with the project it belonged to).
 */
export function IssueProjectLink({
  issue,
  projects,
  milestones,
  canEdit,
}: {
  issue: Issue;
  projects: ProjectSummary[];
  /** The milestones of the linked project; empty when there is none. */
  milestones: Milestone[];
  canEdit: boolean;
}) {
  const mutation = useRefreshingMutation(updateIssue);
  const change = (changes: IssueChanges) => mutation.mutate({ number: issue.number, changes });
  const locked = !canEdit || mutation.isPending;

  return (
    <>
      {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
      <dl className="flex flex-col">
        <PropertyRow label={m.project}>
          <ChipSelect
            label={m.project}
            value={issue.project?.key ?? NONE}
            options={projectOptions(projects, issue.project?.key)}
            disabled={locked}
            onChange={(value) => change({ projectKey: value === NONE ? null : value })}
          />
        </PropertyRow>
        {issue.project ? (
          <PropertyRow label={m.milestone}>
            {milestones.length > 0 ? (
              <ChipSelect
                label={m.milestone}
                value={issue.milestone?.id ?? NONE}
                options={milestoneOptions(milestones)}
                disabled={locked}
                onChange={(value) => change({ milestoneId: value === NONE ? null : value })}
              />
            ) : (
              <span className="flex items-center gap-1.5 font-normal text-muted-foreground">
                <BoxIcon className="size-4" aria-hidden="true" />
                {m.noMilestonesInProject}
              </span>
            )}
          </PropertyRow>
        ) : null}
      </dl>
    </>
  );
}
