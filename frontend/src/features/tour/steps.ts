import { messages } from "@/messages";

const m = messages.tour.steps;

/** A real screen of the app shown with the tour's example data (components/tour-demo.tsx). */
export type DemoScreen = "my" | "list" | "issue" | "overview";

export type TourStep = {
  title: string;
  body: string;
  /**
   * The `data-tour` name of the part this step points at: a part of the app shell, or, on an
   * example screen, a part of that screen. Several elements may carry a name (the sidebar and the
   * top bar of a narrow screen, every line of a list); the first visible one is used. Without a
   * target, or when none is visible, the step speaks for the whole screen.
   */
  target?:
    | "nav"
    | "projects"
    | "user-menu"
    | "issue-toolbar"
    | "issue-row"
    | "issue-body"
    | "issue-properties"
    | "project-description"
    | "milestones"
    | "project-updates";
  /** Shows this screen with example data, to try things on. Nothing done there is saved. */
  demo?: DemoScreen;
};

/**
 * In the order a person meets things: the menu, what each of its places opens, an issue line,
 * the issue it opens part by part, then a project.
 */
export const TOUR_STEPS: TourStep[] = [
  m.welcome,
  { ...m.nav, target: "nav" },
  { ...m.my, demo: "my" },
  { ...m.all, demo: "list" },
  { ...m.toolbar, demo: "list", target: "issue-toolbar" },
  { ...m.row, demo: "list", target: "issue-row" },
  { ...m.issueBody, demo: "issue", target: "issue-body" },
  { ...m.issueProperties, demo: "issue", target: "issue-properties" },
  { ...m.projects, target: "projects" },
  { ...m.overview, demo: "overview", target: "project-description" },
  { ...m.milestones, demo: "overview", target: "milestones" },
  { ...m.updates, demo: "overview", target: "project-updates" },
  { ...m.settings, target: "user-menu" },
  m.done,
];

/** Where pressing an issue's title on an example screen leads. */
export const ISSUE_STEP = TOUR_STEPS.findIndex((step) => step.demo === "issue");
