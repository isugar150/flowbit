import type { IssuePriority, IssueStatus } from "@/features/issues/types";
import { messages } from "@/messages";

const m = messages.tour.steps;

/** What a practice step asks the person to try on the example issues. */
export type PracticeTask = "create" | "status" | "delete";

export type TourStep = {
  title: string;
  body: string;
  /**
   * The `data-tour` name of the part of the app shell this step points at. Several elements may
   * carry a name (the sidebar and the top bar of a narrow screen); the visible one is used.
   * Without a target, or when none is visible, the step is shown in the middle of the screen.
   */
  target?: "nav" | "projects" | "user-menu";
  /** Shows the example issues under the text, to try this on. Nothing done to them is saved. */
  practice?: PracticeTask;
};

export const TOUR_STEPS: TourStep[] = [
  m.welcome,
  { ...m.nav, target: "nav" },
  { ...m.projects, target: "projects" },
  { ...m.create, practice: "create" },
  { ...m.status, practice: "status" },
  { ...m.delete, practice: "delete" },
  { ...m.settings, target: "user-menu" },
  m.done,
];

/** An issue that exists only inside the tour. */
export type ExampleIssue = {
  key: string;
  title: string;
  status: IssueStatus;
  priority: IssuePriority;
};

export const EXAMPLE_KEY_PREFIX = "EX";

export const EXAMPLE_ISSUES: ExampleIssue[] = [
  {
    key: "EX-1",
    title: messages.tour.practice.examples[0],
    status: "IN_PROGRESS",
    priority: "HIGH",
  },
  {
    key: "EX-2",
    title: messages.tour.practice.examples[1],
    status: "READY",
    priority: "URGENT",
  },
  {
    key: "EX-3",
    title: messages.tour.practice.examples[2],
    status: "BACKLOG",
    priority: "MEDIUM",
  },
];
