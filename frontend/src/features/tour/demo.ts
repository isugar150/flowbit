import type { Issue, IssueChanges, Label, NewIssueInput } from "@/features/issues/types";
import type { Milestone } from "@/features/milestones/types";
import type { Project, ProjectMember, ProjectSummary, ProjectUpdate } from "@/features/projects/types";
import { messages } from "@/messages";

const m = messages.tour.demo;

/**
 * Everything the tour's example screens show. The screens are the real ones (the issue list, an
 * issue, a project overview); only the data is made up, and it lives in the browser alone. What
 * they would send to the server is answered by `answer` below instead (lib/api/client.ts
 * `setDemoBackend`), so nothing a newcomer tries here is saved or seen by anyone.
 */
export type Demo = {
  project: Project;
  members: ProjectMember[];
  milestones: Milestone[];
  labels: Label[];
  issues: Issue[];
  updates: ProjectUpdate[];
  nextNumber: number;
};

export const DEMO_PROJECT_ID = "example-project";
export const DEMO_KEY = "EX";

const day = (offset: number, from: Date) => {
  const date = new Date(from.getFullYear(), from.getMonth(), from.getDate() + offset);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
};

/** Reads the clock (the dates are around today), so it is only called in the browser. */
export function createDemo(): Demo {
  const now = new Date();
  const at = now.toISOString();
  const members: ProjectMember[] = m.people.map((name, index) => ({
    userId: `example-user-${index + 1}`,
    loginId: `example${index + 1}`,
    displayName: name,
    avatarUrl: null,
    role: index === 0 ? "MANAGER" : "EDITOR",
  }));
  const person = (index: number) => {
    const { userId, loginId, displayName, avatarUrl } = members[index];
    return { id: userId, loginId, displayName, avatarUrl };
  };
  const labels: Label[] = [
    { id: "example-label-1", name: m.labels[0], color: "#dc2626" },
    { id: "example-label-2", name: m.labels[1], color: "#2563eb" },
  ];
  const milestone: Milestone = {
    id: "example-milestone-1",
    name: m.milestone,
    targetDate: day(14, now),
    issueCount: 3,
    doneCount: 1,
    createdAt: at,
    content: null,
  };
  const project: Project = {
    id: DEMO_PROJECT_ID,
    name: m.project,
    description: m.projectSummary,
    iconUrl: null,
    myRole: "MANAGER",
    memberCount: members.length,
    createdAt: at,
    status: "IN_PROGRESS",
    priority: "HIGH",
    lead: person(0),
    startDate: day(-7, now),
    targetDate: day(21, now),
    labels: [],
    issueCount: 4,
    doneCount: 1,
    currentMilestone: milestone.name,
    health: "ON_TRACK",
    healthUpdatedAt: at,
    subscribed: false,
    member: true,
    archivedAt: null,
    content: null,
  };
  const issue = (number: number, fields: Partial<Issue>): Issue => ({
    id: `example-issue-${number}`,
    key: `${DEMO_KEY}-${number}`,
    number,
    title: m.issues[number - 1] ?? "",
    description: "",
    content: null,
    status: "BACKLOG",
    priority: "NONE",
    assignees: [],
    startDate: null,
    dueDate: null,
    project: { id: project.id, name: project.name },
    milestone: null,
    parent: null,
    ancestors: [],
    completedAt: null,
    labels: [],
    subIssueCount: 0,
    subIssueDoneCount: 0,
    createdBy: person(0),
    version: 1,
    createdAt: at,
    updatedAt: at,
    deletedAt: null,
    ...fields,
  });
  const inMilestone = { id: milestone.id, name: milestone.name, targetDate: milestone.targetDate };
  return {
    project,
    members,
    milestones: [milestone],
    labels,
    issues: [
      issue(1, { status: "IN_PROGRESS", priority: "HIGH", assignees: [person(0)], startDate: day(-1, now), dueDate: day(3, now), milestone: inMilestone, labels: [labels[1]] }),
      issue(2, { status: "READY", priority: "URGENT", assignees: [person(1)], dueDate: day(1, now), milestone: inMilestone, labels: [labels[0]] }),
      issue(3, { status: "BACKLOG", priority: "MEDIUM", assignees: [person(0)], dueDate: day(9, now) }),
      issue(4, { status: "DONE", priority: "LOW", assignees: [person(1)], milestone: inMilestone }),
    ],
    updates: [{ id: "example-update-1", health: "ON_TRACK", body: m.update, author: person(0), createdAt: at }],
    nextNumber: 5,
  };
}

type Answer = { demo: Demo; result: unknown };

/** The fields of an issue that a request names by id, as the server would send them back. */
function resolve(demo: Demo, changes: IssueChanges | NewIssueInput): Partial<Issue> {
  const fields: Partial<Issue> = {};
  if (changes.title !== undefined) fields.title = changes.title;
  if (changes.content !== undefined) fields.content = changes.content;
  if (changes.status !== undefined) fields.status = changes.status;
  if (changes.priority !== undefined) fields.priority = changes.priority;
  if (changes.startDate !== undefined) fields.startDate = changes.startDate;
  if (changes.dueDate !== undefined) fields.dueDate = changes.dueDate;
  if (changes.assigneeIds !== undefined) {
    fields.assignees = (changes.assigneeIds ?? []).flatMap((id) =>
      demo.members
        .filter((item) => item.userId === id)
        .map((member) => ({ id: member.userId, loginId: member.loginId, displayName: member.displayName, avatarUrl: null })),
    );
  }
  if (changes.milestoneId !== undefined) {
    const milestone = demo.milestones.find((item) => item.id === changes.milestoneId);
    fields.milestone = milestone ? { id: milestone.id, name: milestone.name, targetDate: milestone.targetDate } : null;
  }
  if (changes.labelIds !== undefined) fields.labels = demo.labels.filter((label) => changes.labelIds?.includes(label.id));
  if (changes.projectId !== undefined) {
    fields.project = changes.projectId === demo.project.id ? { id: demo.project.id, name: demo.project.name } : null;
  }
  return fields;
}

/**
 * What the server would have answered, and the example data after it. Only what the example
 * screens can do is played out; anything else succeeds and changes nothing.
 */
export function answer(demo: Demo, method: string, path: string, body: unknown): Answer {
  const [route] = path.split("?");
  const issueNumber = Number(/\/issues\/(\d+)$/.exec(route)?.[1]);
  const milestoneId = /\/milestones\/([^/]+)$/.exec(route)?.[1];
  const now = new Date().toISOString();

  if (method === "GET") {
    if (issueNumber) return { demo, result: demo.issues.find((issue) => issue.number === issueNumber) ?? null };
    if (route.endsWith("/issues")) return { demo, result: demo.issues };
    if (route.endsWith("/milestones")) return { demo, result: demo.milestones };
    return { demo, result: [] };
  }
  if (method === "POST" && route.endsWith("/issues")) {
    const input = body as NewIssueInput;
    const template = demo.issues[0];
    const created: Issue = {
      ...template,
      id: `example-issue-${demo.nextNumber}`,
      key: `${DEMO_KEY}-${demo.nextNumber}`,
      number: demo.nextNumber,
      description: "",
      assignees: [],
      milestone: null,
      labels: [],
      startDate: null,
      dueDate: null,
      createdAt: now,
      updatedAt: now,
      ...resolve(demo, input),
    };
    return { demo: { ...demo, issues: [...demo.issues, created], nextNumber: demo.nextNumber + 1 }, result: created };
  }
  if (issueNumber && method === "PATCH") {
    const issues = demo.issues.map((issue) =>
      issue.number === issueNumber ? { ...issue, ...resolve(demo, body as IssueChanges), updatedAt: now } : issue,
    );
    return { demo: { ...demo, issues }, result: issues.find((issue) => issue.number === issueNumber) };
  }
  if (issueNumber && method === "DELETE") {
    return { demo: { ...demo, issues: demo.issues.filter((issue) => issue.number !== issueNumber) }, result: undefined };
  }
  if (method === "POST" && route.endsWith("/milestones")) {
    const input = body as { name: string; targetDate?: string | null };
    const created: Milestone = {
      id: `example-milestone-${demo.milestones.length + 1}-${demo.nextNumber}`,
      name: input.name,
      targetDate: input.targetDate ?? null,
      issueCount: 0,
      doneCount: 0,
      createdAt: now,
      content: null,
    };
    return { demo: { ...demo, milestones: [...demo.milestones, created] }, result: created };
  }
  if (milestoneId && method === "PATCH") {
    const milestones = demo.milestones.map((item) => (item.id === milestoneId ? { ...item, ...(body as Partial<Milestone>) } : item));
    return { demo: { ...demo, milestones }, result: milestones.find((item) => item.id === milestoneId) };
  }
  if (milestoneId && method === "DELETE") {
    return { demo: { ...demo, milestones: demo.milestones.filter((item) => item.id !== milestoneId) }, result: undefined };
  }
  if (method === "POST" && route.endsWith("/updates")) {
    const input = body as Pick<ProjectUpdate, "health" | "body">;
    const created: ProjectUpdate = { id: `example-update-${demo.updates.length + 1}`, health: input.health, body: input.body, author: demo.updates[0].author, createdAt: now };
    return { demo: { ...demo, updates: [created, ...demo.updates], project: { ...demo.project, health: created.health } }, result: created };
  }
  if (method === "PATCH" && route.endsWith(`/projects/${DEMO_PROJECT_ID}`)) {
    const project = { ...demo.project, ...(body as Partial<Project>) };
    return { demo: { ...demo, project }, result: project };
  }
  return { demo, result: {} };
}

/** The example project as the lists of projects have it. */
export const asSummary = (project: Project): ProjectSummary => project;
