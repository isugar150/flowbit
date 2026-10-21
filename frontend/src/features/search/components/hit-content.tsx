import { BoxIcon, DiamondIcon, FileTextIcon } from "lucide-react";
import { StatusIcon, statusLabel } from "@/features/issues/meta";
import { messages } from "@/messages";
import { Highlight } from "../highlight";
import type { DocumentHit, IssueHit, MilestoneHit, ProjectHit } from "../types";

const m = messages.search;

export function issueHref(hit: IssueHit): string {
  return `/issue/${hit.key}`;
}

export function documentHref(hit: DocumentHit): string {
  return `/doc/${hit.id}`;
}

export function projectHref(hit: ProjectHit): string {
  return `/project/${hit.id}`;
}

export function milestoneHref(hit: MilestoneHit): string {
  return `/project/${hit.project.id}/milestone/${hit.id}`;
}

/** What one found issue shows: status, number, title, project, and where the words were found. */
export function IssueHitContent({ hit, words }: { hit: IssueHit; words: string[] }) {
  return (
    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
      <span className="flex min-w-0 items-center gap-2">
        <StatusIcon status={hit.status} />
        <span className="sr-only">{statusLabel(hit.status)}</span>
        <span className="shrink-0 text-[0.75rem] leading-5 text-muted-foreground tabular-nums">{hit.key}</span>
        <span className="min-w-0 truncate font-medium">
          <Highlight text={hit.title} words={words} />
        </span>
        {hit.project ? (
          <span className="ml-auto shrink-0 truncate pl-2 text-[0.75rem] leading-5 text-muted-foreground max-w-40">
            {hit.project.name}
          </span>
        ) : null}
      </span>
      {hit.snippet ? (
        <span className="truncate pl-6 text-[0.8125rem] leading-5 text-muted-foreground">
          {hit.match === "COMMENT" ? <span className="mr-1.5 text-foreground/70">{m.inComment}</span> : null}
          <Highlight text={hit.snippet} words={words} />
        </span>
      ) : null}
    </span>
  );
}

/** What one found document shows: its title, where it belongs, and a piece of its body. */
export function DocumentHitContent({ hit, words }: { hit: DocumentHit; words: string[] }) {
  const place = hit.issue ? hit.issue.key : hit.project?.name;
  return (
    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
      <span className="flex min-w-0 items-center gap-2">
        <FileTextIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="min-w-0 truncate font-medium">
          {hit.title ? <Highlight text={hit.title} words={words} /> : <span className="text-muted-foreground">{m.untitled}</span>}
        </span>
        {place ? (
          <span className="ml-auto shrink-0 truncate pl-2 text-[0.75rem] leading-5 text-muted-foreground max-w-40">{place}</span>
        ) : null}
      </span>
      {hit.snippet ? (
        <span className="truncate pl-6 text-[0.8125rem] leading-5 text-muted-foreground">
          <Highlight text={hit.snippet} words={words} />
        </span>
      ) : null}
    </span>
  );
}

/** What one found project shows: its name and a piece of its overview. */
export function ProjectHitContent({ hit, words }: { hit: ProjectHit; words: string[] }) {
  return (
    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
      <span className="flex min-w-0 items-center gap-2">
        <BoxIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="min-w-0 truncate font-medium">
          <Highlight text={hit.name} words={words} />
        </span>
      </span>
      {hit.snippet ? (
        <span className="truncate pl-6 text-[0.8125rem] leading-5 text-muted-foreground">
          <Highlight text={hit.snippet} words={words} />
        </span>
      ) : null}
    </span>
  );
}

/** What one found milestone shows: its name, its project, and a piece of what is written in it. */
export function MilestoneHitContent({ hit, words }: { hit: MilestoneHit; words: string[] }) {
  return (
    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
      <span className="flex min-w-0 items-center gap-2">
        <DiamondIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="min-w-0 truncate font-medium">
          <Highlight text={hit.name} words={words} />
        </span>
        <span className="ml-auto shrink-0 truncate pl-2 text-[0.75rem] leading-5 text-muted-foreground max-w-40">{hit.project.name}</span>
      </span>
      {hit.snippet ? (
        <span className="truncate pl-6 text-[0.8125rem] leading-5 text-muted-foreground">
          <Highlight text={hit.snippet} words={words} />
        </span>
      ) : null}
    </span>
  );
}
