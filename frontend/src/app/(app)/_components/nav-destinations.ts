import { CircleDotIcon, CircleUserRoundIcon, LayoutGridIcon, type LucideIcon } from "lucide-react";
import type { ProjectSummary } from "@/features/projects/types";
import { messages } from "@/messages";

/** The places at the top of the navigation, the same in the sidebar and in the menu of a narrow screen. */
export const NAV_DESTINATIONS: { href: string; label: string; icon: LucideIcon; exact: boolean }[] = [
  { href: "/my-issues", label: messages.myIssues.title, icon: CircleUserRoundIcon, exact: false },
  { href: "/issues", label: messages.issues.all, icon: CircleDotIcon, exact: true },
  { href: "/", label: messages.projects.all, icon: LayoutGridIcon, exact: true },
];

/** Whether `pathname` is `href` itself or, unless `exact`, a page under it. */
export function isCurrentPath(pathname: string, href: string, exact = false): boolean {
  return pathname === href || (!exact && pathname.startsWith(`${href}/`));
}

/** "내 프로젝트" lists the ones this person can enter; the rest are on the all-projects page. */
export function enterableProjects(projects: ProjectSummary[]): ProjectSummary[] {
  // The ones the person was added to and the ones open to every member (D-192). A project they cannot
  // enter is reached through 전체 프로젝트 only.
  return projects.filter((project) => project.myRole !== null);
}
