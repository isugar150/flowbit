import { BoxIcon, CircleSlashIcon, DiamondIcon, ListIcon, UserIcon } from "lucide-react";
import { PersonAvatar, personName } from "@/components/person";
import { ProjectIcon } from "@/components/project-icon";
import { milestoneSummary, type Milestone } from "@/features/milestones/types";
import type { ProjectSummary } from "@/features/projects/types";
import { messages } from "@/messages";
import { PriorityIcon, STATUS_ORDER, StatusIcon, priorityLabel, statusLabel } from "./meta";
import type { Issue, IssuePerson, IssuePriority } from "./types";

const m = messages.issues;

/** What a list of issues is grouped by; `?group=` in the address. The first is the default. */
export const ISSUE_GROUPS = ["status", "priority", "assignee", "project", "milestone", "none"] as const;
export type IssueGrouping = (typeof ISSUE_GROUPS)[number];
export const DEFAULT_ISSUE_GROUPING: IssueGrouping = "status";

/** One group of a list: its heading and the issues in it, in the order they came. */
export type IssueGroup = {
  key: string;
  label: string;
  icon: React.ReactNode;
  /** Beside the heading: how far a milestone is ("6/10 · 60% · 10-31"). */
  meta?: string;
  issues: Issue[];
};

const PRIORITY_ORDER: IssuePriority[] = ["URGENT", "HIGH", "MEDIUM", "LOW", "NONE"];
const NONE_KEY = "none";
const muted = "size-4 shrink-0 text-muted-foreground";

/**
 * Splits the issues into the groups of `grouping`, empty groups left out. Groups with a natural
 * order keep it (status, priority); people, projects and milestones go by name, with "none" last.
 * An issue given to several people is in the group of each of them.
 */
export function groupIssues(
  issues: Issue[],
  grouping: IssueGrouping,
  projects: ProjectSummary[],
  /** Known milestones with their progress, for the milestone headings; may be empty. */
  milestones: Milestone[] = [],
): IssueGroup[] {
  switch (grouping) {
    case "status":
      return fixed(
        STATUS_ORDER.map((status) => ({ key: status, label: statusLabel(status), icon: <StatusIcon status={status} /> })),
        issues,
        (issue) => [issue.status],
      );
    case "priority":
      return fixed(
        PRIORITY_ORDER.map((priority) => ({ key: priority, label: priorityLabel(priority), icon: <PriorityIcon priority={priority} /> })),
        issues,
        (issue) => [issue.priority],
      );
    case "assignee": {
      const people = new Map<string, IssuePerson>();
      for (const issue of issues) for (const person of issue.assignees) people.set(person.id, person);
      return fixed(
        [
          ...byName([...people.values()].map((person) => ({ key: person.id, label: personName(person), icon: <PersonAvatar person={person} /> }))),
          { key: NONE_KEY, label: m.noAssignee, icon: <UserIcon className={muted} aria-hidden="true" /> },
        ],
        issues,
        (issue) => (issue.assignees.length > 0 ? issue.assignees.map((person) => person.id) : [NONE_KEY]),
      );
    }
    case "project": {
      const named = new Map<string, string>();
      for (const issue of issues) if (issue.project) named.set(issue.project.id, issue.project.name);
      const iconOf = (id: string) => projects.find((project) => project.id === id)?.iconUrl ?? null;
      return fixed(
        [
          ...byName([...named].map(([id, name]) => ({ key: id, label: name, icon: <ProjectIcon iconUrl={iconOf(id)} /> }))),
          { key: NONE_KEY, label: m.noProject, icon: <BoxIcon className={muted} aria-hidden="true" /> },
        ],
        issues,
        (issue) => [issue.project?.id ?? NONE_KEY],
      );
    }
    case "milestone": {
      const named = new Map<string, string>();
      for (const issue of issues) if (issue.milestone) named.set(issue.milestone.id, issue.milestone.name);
      return fixed(
        [
          ...byName(
            [...named].map(([id, name]) => {
              const known = milestones.find((milestone) => milestone.id === id);
              return { key: id, label: name, icon: <DiamondIcon className={muted} aria-hidden="true" />, meta: known ? milestoneSummary(known) : undefined };
            }),
          ),
          { key: NONE_KEY, label: m.noMilestone, icon: <CircleSlashIcon className={muted} aria-hidden="true" /> },
        ],
        issues,
        (issue) => [issue.milestone?.id ?? NONE_KEY],
      );
    }
    case "none":
      return issues.length > 0 ? [{ key: "all", label: m.groupAll, icon: <ListIcon className={muted} aria-hidden="true" />, issues }] : [];
  }
}

/** The groups in the order given, each with the issues whose keys name it. */
function fixed(
  heads: Omit<IssueGroup, "issues">[],
  issues: Issue[],
  keysOf: (issue: Issue) => string[],
): IssueGroup[] {
  const members = new Map<string, Issue[]>();
  for (const issue of issues) for (const key of keysOf(issue)) members.set(key, [...(members.get(key) ?? []), issue]);
  return heads.map((head) => ({ ...head, issues: members.get(head.key) ?? [] })).filter((group) => group.issues.length > 0);
}

function byName<T extends { label: string }>(items: T[]): T[] {
  return [...items].sort((a, b) => a.label.localeCompare(b.label, "ko"));
}
