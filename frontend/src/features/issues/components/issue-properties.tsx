"use client";

import { CircleSlashIcon } from "lucide-react";
import { ProjectIcon } from "@/components/project-icon";
import { CustomProperties } from "@/features/custom-properties/components/custom-properties";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { updateIssue } from "../api";
import { NONE, priorityOptions, statusOptions } from "../options";
import type { Issue, IssueChanges, IssuePerson, IssuePriority, IssueStatus, Label } from "../types";
import { AssigneeChip } from "./assignee-chip";
import { ChipSelect, type ChipOption } from "./chip-select";
import { DateRangeChip } from "./date-chips";
import { IssueParentField } from "./issue-parent-field";
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
  assigneeIds: string[];
  startDate: string | null;
  dueDate: string | null;
  labelIds: string[];
  projectId: string | null;
};

/**
 * The properties of an issue in three groups: its properties (status, priority, assignees, dates,
 * and whatever people added to it), its labels, and the project it is linked to. Every value is a
 * quiet row that opens a menu. The same panel is used where an issue is made and where it is
 * changed; it only reports what was picked and leaves saving to whoever shows it.
 */
export function IssuePropertyFields({
  values,
  onChange,
  members,
  labels,
  projects,
  assignees,
  followsParent = false,
  afterDueDate,
  after,
  disabled,
}: {
  values: IssuePropertyValues;
  onChange: (changes: Partial<IssuePropertyValues>) => void;
  /** Who the issue can be given to: the members of its project, or everyone when it has none. */
  members: ProjectMember[];
  /** Every label in the workspace. */
  labels: Label[];
  projects: ProjectSummary[];
  /** The saved assignees of an existing issue. */
  assignees?: IssuePerson[];
  /** A sub-issue: its project is its parent's and cannot be changed here (D-129). */
  followsParent?: boolean;
  /** Right under the due date, in the same group: the properties people added to an existing issue and the way to add one (D-189). */
  afterDueDate?: React.ReactNode;
  /** A group of its own below the project: for an existing issue, the issue it hangs under. */
  after?: React.ReactNode;
  disabled?: boolean;
}) {
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
          <AssigneeChip
            members={members}
            current={assignees}
            value={values.assigneeIds}
            disabled={disabled}
            onChange={(assigneeIds) => onChange({ assigneeIds })}
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
        {afterDueDate}
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
            disabled={disabled || followsParent}
            onChange={(value) => onChange({ projectId: value === NONE ? null : value })}
          />
        </Row>
        {followsParent ? (
          <p className="text-xs leading-4 text-muted-foreground">{m.followsParentHint}</p>
        ) : values.projectId ? null : (
          <p className="text-xs leading-4 text-muted-foreground">{m.noProjectHint}</p>
        )}
      </Section>
      {after}
    </div>
  );
}

/**
 * The side panel of an existing issue: each pick is saved at once; there is no edit mode. A pick
 * shows right away and is saved behind it (useOptimisticMutation).
 *
 * The issue is its own thing and the project only a link, so the project can be changed or
 * removed at any time. What people added to the issue on their own sits in the first group, with
 * the way to add one right under the due date (D-186, D-189), and below the project is the issue
 * it hangs under, found by searching.
 */
export function IssueProperties({
  issue,
  members,
  labels,
  projects,
  canEdit,
}: {
  issue: Issue;
  members: ProjectMember[];
  labels: Label[];
  projects: ProjectSummary[];
  canEdit: boolean;
}) {
  const saved: IssuePropertyValues = {
    status: issue.status,
    priority: issue.priority,
    assigneeIds: issue.assignees.map((person) => person.id),
    startDate: issue.startDate,
    dueDate: issue.dueDate,
    labelIds: issue.labels.map((label) => label.id),
    projectId: issue.project?.id ?? null,
  };
  const mutation = useOptimisticMutation(
    saved,
    (values, { changes }: { number: number; changes: IssueChanges }) => ({ ...values, ...changes }),
    updateIssue,
  );

  return (
    <div className="flex flex-col gap-3">
      {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
      <IssuePropertyFields
        values={mutation.value}
        onChange={(changes) => mutation.mutate({ number: issue.number, changes })}
        members={members}
        labels={labels}
        projects={projects}
        assignees={issue.assignees}
        followsParent={issue.parent !== null}
        disabled={!canEdit}
        afterDueDate={<CustomProperties owner={{ type: "issue", id: issue.id }} members={members} watch={issue} layout="panel" />}
        after={
          <Section title={m.parentField.title}>
            <IssueParentField issue={issue} canEdit={canEdit} />
          </Section>
        }
      />
    </div>
  );
}
