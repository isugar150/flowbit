"use client";

import { CircleSlashIcon, DiamondIcon } from "lucide-react";
import { ProjectIcon } from "@/components/project-icon";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { Milestone } from "@/features/milestones/types";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { NONE, assigneeOptions, milestoneOptions, priorityOptions, statusOptions } from "../options";
import type { Issue, IssuePriority, IssueStatus, Label } from "../types";
import { ChipSelect, type ChipOption } from "./chip-select";
import { DateRangeChip } from "./date-chips";
import { LabelChip } from "./label-chip";

const m = messages.issues;

/** A project to link an issue to, or none. Only projects the person may put issues into are offered. */
export function projectOptions(projects: ProjectSummary[], current?: string | null): ChipOption<string>[] {
  return [
    { value: NONE, label: m.noProject, icon: <CircleSlashIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...projects
      .filter((project) => (project.myRole && project.myRole !== "VIEWER") || project.id === current)
      .map((project) => ({ value: project.id, label: project.name, icon: <ProjectIcon iconUrl={project.iconUrl} /> })),
  ];
}

/** A titled group in the issue's side panel. No frame: the heading and the space around it set it apart. */
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-1">
      <h3 className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">{title}</h3>
      {children}
    </section>
  );
}

/** One value in a section. `id` lets others see who is pointing at it (features/realtime/presence-focus.tsx). */
function Row({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <div data-presence-id={`property:${id}`} className="flex min-h-8 items-center">
      {children}
    </div>
  );
}

/** The properties an issue is made or changed with, as the panel shows them. */
export type IssuePropertyValues = {
  status: IssueStatus;
  priority: IssuePriority;
  assigneeId: string | null;
  startDate: string | null;
  dueDate: string | null;
  labelIds: string[];
  projectId: string | null;
  milestoneId: string | null;
};

/**
 * The properties of an issue in three groups: its properties (status, priority, assignee, dates),
 * its labels, and the project it is linked to with the milestone inside that project. Every value
 * is a quiet row that opens a menu. The same panel is used where an issue is made and where it is
 * changed; it only reports what was picked and leaves saving to whoever shows it.
 */
export function IssuePropertyFields({
  values,
  onChange,
  members,
  labels,
  projects,
  milestones,
  disabled,
}: {
  values: IssuePropertyValues;
  onChange: (changes: Partial<IssuePropertyValues>) => void;
  /** Who the issue can be given to: the members of its project, or everyone when it has none. */
  members: ProjectMember[];
  /** Every label in the workspace. */
  labels: Label[];
  projects: ProjectSummary[];
  /** The milestones of the chosen project; empty when there is none. */
  milestones: Milestone[];
  disabled?: boolean;
}) {
  // "No milestone" reads as an invitation to set one, the way an empty due date does.
  const milestoneChoices = milestoneOptions(milestones).map((option) =>
    option.value === NONE
      ? { ...option, label: m.setMilestone, icon: <DiamondIcon className="size-4 text-muted-foreground" aria-hidden /> }
      : option,
  );

  return (
    <div className="flex flex-col gap-6">
      <Section title={messages.projects.properties}>
        <Row id={m.status}>
          <ChipSelect
            plain
            label={m.status}
            value={values.status}
            options={statusOptions}
            disabled={disabled}
            onChange={(status) => onChange({ status })}
          />
        </Row>
        <Row id={m.priority}>
          <ChipSelect
            plain
            label={m.priority}
            value={values.priority}
            options={priorityOptions}
            disabled={disabled}
            onChange={(priority) => onChange({ priority })}
          />
        </Row>
        <Row id={m.assignee}>
          <ChipSelect
            plain
            label={m.assignee}
            value={values.assigneeId ?? NONE}
            options={assigneeOptions(members)}
            disabled={disabled}
            onChange={(value) => onChange({ assigneeId: value === NONE ? null : value })}
          />
        </Row>
        <Row id={m.dueDate}>
          {/* One day, or a first and a last day. */}
          <DateRangeChip
            plain
            singleIsEnd
            label={m.dueDate}
            placeholder={m.noDueDate}
            start={values.startDate}
            end={values.dueDate}
            disabled={disabled}
            onChange={({ start, end }) => onChange({ startDate: start, dueDate: end })}
          />
        </Row>
      </Section>
      <Section title={m.labels}>
        <Row id={m.labels}>
          <LabelChip asPills labels={labels} value={values.labelIds} disabled={disabled} onChange={(labelIds) => onChange({ labelIds })} />
        </Row>
      </Section>
      <Section title={m.project}>
        <Row id={m.project}>
          <ChipSelect
            plain
            label={m.project}
            value={values.projectId ?? NONE}
            options={projectOptions(projects, values.projectId)}
            disabled={disabled}
            onChange={(value) => onChange({ projectId: value === NONE ? null : value })}
          />
        </Row>
        {/* The milestone hangs under its project: it only exists once a project is chosen. */}
        {values.projectId ? (
          <Row id={m.milestone}>
            <span
              aria-hidden="true"
              className="mr-1 ml-1.5 h-4 w-3 shrink-0 self-start rounded-bl-md border-b border-l border-foreground/25"
            />
            {milestones.length > 0 ? (
              <ChipSelect
                plain
                label={m.milestone}
                value={values.milestoneId ?? NONE}
                options={milestoneChoices}
                disabled={disabled}
                onChange={(value) => onChange({ milestoneId: value === NONE ? null : value })}
              />
            ) : (
              <span className="flex h-8 items-center gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
                <DiamondIcon className="size-4" aria-hidden="true" />
                {m.noMilestonesInProject}
              </span>
            )}
          </Row>
        ) : null}
      </Section>
    </div>
  );
}

/**
 * The side panel of an existing issue: each pick is saved at once; there is no edit mode.
 *
 * The issue is its own thing and the project only a link, so the project can be changed or
 * removed at any time (the milestone goes with the project it belonged to).
 */
export function IssueProperties({
  issue,
  members,
  labels,
  projects,
  milestones,
  canEdit,
}: {
  issue: Issue;
  members: ProjectMember[];
  labels: Label[];
  projects: ProjectSummary[];
  milestones: Milestone[];
  canEdit: boolean;
}) {
  const mutation = useRefreshingMutation(updateIssue);

  return (
    <div className="flex flex-col gap-3">
      {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
      <IssuePropertyFields
        values={{
          status: issue.status,
          priority: issue.priority,
          assigneeId: issue.assignee?.id ?? null,
          startDate: issue.startDate,
          dueDate: issue.dueDate,
          labelIds: issue.labels.map((label) => label.id),
          projectId: issue.project?.id ?? null,
          milestoneId: issue.milestone?.id ?? null,
        }}
        onChange={(changes) => mutation.mutate({ number: issue.number, changes })}
        members={members}
        labels={labels}
        projects={projects}
        milestones={milestones}
        disabled={!canEdit || mutation.isPending}
      />
    </div>
  );
}
