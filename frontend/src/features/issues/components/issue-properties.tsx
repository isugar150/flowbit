"use client";

import { CircleSlashIcon, RepeatIcon } from "lucide-react";
import { ProjectIcon } from "@/components/project-icon";
import { CustomProperties } from "@/features/custom-properties/components/custom-properties";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { ProjectMember, ProjectSummary } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { useQuery } from "@tanstack/react-query";
import { cycleSpan, getCycles } from "@/features/cycles/api";
import { updateIssue } from "../api";
import { previousOf } from "../undo";
import { updateIssueUndoable, type UndoableChange } from "../undoable";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { NONE, estimateOptions, priorityOptions } from "../options";
import { defaultStatusOf } from "../status-defaults";
import { statusChoices, useIssueStatuses } from "../statuses";
import type { Issue, IssuePerson, IssuePriority, IssueStatus, Label } from "../types";
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
  /** The status picked; left out, the first one of `status`'s category. */
  statusId?: string;
  priority: IssuePriority;
  /** Story points, for a project that turned estimates on. */
  estimate?: number | null;
  assigneeIds: string[];
  startDate: string | null;
  dueDate: string | null;
  labelIds: string[];
  projectId: string | null;
};

/** The cycle an existing issue is planned in, for a project that runs in cycles (D-232): this one, the next, or an earlier one. */
function CycleSection({ issue, canEdit }: { issue: Issue; canEdit: boolean }) {
  const projectId = issue.project?.id;
  const cycles = useQuery({
    queryKey: ["cycles", projectId],
    queryFn: () => getCycles(projectId as string),
    enabled: Boolean(projectId),
    staleTime: 30_000,
  });
  const mutation = useRefreshingMutation(updateIssue);
  const data = cycles.data;
  if (!data?.settings.enabled) return null;
  const choices = [
    ...(data.current ? [{ cycle: data.current, label: messages.cycles.current }] : []),
    ...(data.upcoming ? [{ cycle: data.upcoming, label: messages.cycles.upcoming }] : []),
    ...data.past.map((cycle) => ({ cycle, label: "" })),
  ];
  // An issue can sit in a cycle older than the ones listed: it stays choosable while it is the value.
  if (issue.cycle && !choices.some((choice) => choice.cycle.id === issue.cycle?.id)) {
    choices.push({ cycle: { ...issue.cycle, total: 0, done: 0 }, label: "" });
  }
  const options: ChipOption<string>[] = [
    { value: NONE, label: messages.cycles.none, icon: <CircleSlashIcon className="size-4 text-muted-foreground" aria-hidden /> },
    ...choices.map(({ cycle, label }) => ({ value: cycle.id, label: messages.cycles.option(label, cycle.number, cycleSpan(cycle)), icon: <RepeatIcon className="size-4 text-muted-foreground" aria-hidden /> })),
  ];
  return (
    <Section title={messages.cycles.property}>
      <Row id={messages.cycles.property}>
        <ChipSelect
          plain
          label={messages.cycles.property}
          value={issue.cycle?.id ?? NONE}
          options={options}
          disabled={!canEdit || mutation.isPending}
          onChange={(id) => mutation.mutate({ number: issue.number, changes: { cycleId: id === NONE ? null : id } })}
        />
      </Row>
    </Section>
  );
}

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
  const statuses = useIssueStatuses();
  const estimatesOn = projects.find((project) => project.id === values.projectId)?.estimatesEnabled === true;
  return (
    <div className="flex flex-col gap-6">
      <Section title={messages.projects.properties}>
        <Row id={m.status}>
          <ChipSelect
            plain
            label={m.status}
            value={values.statusId ?? defaultStatusOf(statuses, values.status).id}
            options={statusChoices(statuses)}
            disabled={disabled}
            onChange={(statusId) => onChange({ statusId, status: statuses.find((status) => status.id === statusId)?.category ?? values.status })}
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
        {estimatesOn ? (
          <Row id={m.estimate}>
            <ChipSelect
              plain
              label={m.estimate}
              value={values.estimate == null ? NONE : String(values.estimate)}
              options={estimateOptions(values.estimate ?? null)}
              disabled={disabled}
              onChange={(points) => onChange({ estimate: points === NONE ? null : Number(points) })}
            />
          </Row>
        ) : null}
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
    statusId: issue.statusId,
    priority: issue.priority,
    estimate: issue.estimate,
    assigneeIds: issue.assignees.map((person) => person.id),
    startDate: issue.startDate,
    dueDate: issue.dueDate,
    labelIds: issue.labels.map((label) => label.id),
    projectId: issue.project?.id ?? null,
  };
  const mutation = useOptimisticMutation(
    saved,
    (values, { changes }: UndoableChange) => ({ ...values, ...changes }),
    updateIssueUndoable,
  );

  return (
    <div className="flex flex-col gap-3">
      {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
      <IssuePropertyFields
        values={mutation.value}
        onChange={(changes) => mutation.mutate({ number: issue.number, changes, key: issue.key, undoWith: previousOf(mutation.value, changes) })}
        members={members}
        labels={labels}
        projects={projects}
        assignees={issue.assignees}
        followsParent={issue.parent !== null}
        disabled={!canEdit}
        afterDueDate={<CustomProperties owner={{ type: "issue", id: issue.id }} members={members} watch={issue} layout="panel" />}
        after={
          <>
            <CycleSection issue={issue} canEdit={canEdit} />
            <Section title={m.parentField.title}>
              <IssueParentField issue={issue} canEdit={canEdit} />
            </Section>
          </>
        }
      />
    </div>
  );
}
