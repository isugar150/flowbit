import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/*
 * The shapes a screen takes while it waits for its data. Each piece is drawn at the size of the thing it
 * stands in for (the header is h-11 like PageHeader, a list line h-10 like an issue line, ...), so what
 * arrives replaces it without the page growing or shrinking. Compose them in a `loading.tsx` or a
 * <Suspense fallback>; nothing here reads data.
 */

/** Row widths that repeat, so a list of placeholders does not look like a wall of equal bars. */
const TITLE_WIDTHS = ["w-2/5", "w-3/5", "w-1/3", "w-1/2", "w-2/3", "w-1/4", "w-3/5", "w-2/5"];

/**
 * Fills what is left of the content panel and cuts off whatever does not fit, so a skeleton can list more
 * lines than the screen holds without making the panel scroll.
 */
export function SkeletonFill({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div aria-hidden="true" className={cn("flex min-h-0 flex-1 flex-col overflow-hidden", className)}>
      {children}
    </div>
  );
}

/**
 * One line of text: the box has the line's height and the bar inside is a little shorter than the
 * letters, so a stack of lines takes the room the real text will.
 */
export function TextLine({ lineHeight = "h-5", bar = "h-3.5", className }: { lineHeight?: string; bar?: string; className?: string }) {
  return (
    <div className={cn("flex items-center", lineHeight)}>
      <Skeleton className={cn(bar, "max-w-full", className)} />
    </div>
  );
}

/** The page header (PageHeader): a path, and the page's actions at the right. */
export function HeaderSkeleton({ crumbs = ["w-24"], actions = false }: { crumbs?: string[]; actions?: boolean }) {
  return (
    <div aria-hidden="true" className="flex h-11 shrink-0 items-center justify-between gap-3 px-4">
      <div className="flex min-w-0 items-center gap-2 px-1">
        {crumbs.map((width, index) => (
          <Skeleton key={index} className={cn("h-4", width)} />
        ))}
      </div>
      {actions ? <Skeleton className="h-7 w-24" /> : null}
    </div>
  );
}

/** The row of rounded tabs under the header (PillTabs). */
export function TabsSkeleton({ count = 2 }: { count?: number }) {
  return (
    <div aria-hidden="true" className="flex shrink-0 items-center gap-1.5 px-4 pb-2">
      {Array.from({ length: count }, (_, index) => (
        <Skeleton key={index} className="h-7 w-16 rounded-full max-md:h-9" />
      ))}
    </div>
  );
}

/** The tool line above an issue list: search and filter on the left, display options and the view switch on the right. */
export function ListToolbarSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-2 px-4 pb-2">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-2">
        <div className="flex items-center gap-2">
          <Skeleton className="h-7 w-44 rounded-full max-md:h-9" />
          <Skeleton className="h-7 w-16 rounded-full max-md:h-9" />
        </div>
        <div className="ml-auto flex items-center gap-2">
          <Skeleton className="h-7 w-20 rounded-full max-md:h-9 max-sm:hidden" />
          <Skeleton className="h-7 w-20 rounded-full max-md:h-9 max-sm:hidden" />
          <Skeleton className="h-7 w-24 rounded-full max-md:h-9" />
        </div>
      </div>
    </div>
  );
}

/** A line of an issue list: status, priority, key, title, and the person and date at the right. */
function IssueLineSkeleton({ width }: { width: string }) {
  return (
    <div className="flex h-10 items-center gap-2 border-b pr-5 pl-3 max-md:h-12">
      <span className="flex w-7 shrink-0 justify-center">
        <Skeleton className="size-4 rounded-full" />
      </span>
      <span className="flex w-4 shrink-0">
        <Skeleton className="size-4 rounded-sm" />
      </span>
      <span className="flex w-16 shrink-0">
        <Skeleton className="h-3 w-12" />
      </span>
      <span className="flex min-w-0 flex-1">
        <Skeleton className={cn("h-3.5 max-w-full", width)} />
      </span>
      <Skeleton className="size-5 shrink-0 rounded-full max-sm:hidden" />
      <Skeleton className="h-3 w-12 shrink-0 max-sm:hidden" />
    </div>
  );
}

/**
 * An issue list: the column heads, then groups of lines under a heading bar. `groups` is the number of lines
 * in each group. More lines than a screen holds are drawn on purpose; the extra ones are cut off.
 */
export function IssueListSkeleton({ groups = [6, 8, 8, 8] }: { groups?: number[] }) {
  // How many lines come before each group, so the title widths keep varying from one group to the next.
  const before = groups.map((_, group) => groups.slice(0, group).reduce((sum, lines) => sum + lines, 0));
  return (
    <SkeletonFill>
      <div className="h-8 shrink-0" />
      {groups.map((lines, group) => (
        <div key={group} className="shrink-0">
          <div className="flex h-9 items-center gap-2 border-y bg-muted px-5">
            <Skeleton className="size-4 rounded-full bg-foreground/10" />
            <Skeleton className="h-3.5 w-24 bg-foreground/10" />
          </div>
          {Array.from({ length: lines }, (_, line) => (
            <IssueLineSkeleton key={line} width={TITLE_WIDTHS[(before[group] + line) % TITLE_WIDTHS.length]} />
          ))}
        </div>
      ))}
    </SkeletonFill>
  );
}

/** The list of projects as a table (ProjectTable): the heading line, then one line per project. */
export function ProjectTableSkeleton({ rows = 14 }: { rows?: number }) {
  return (
    <SkeletonFill>
      <div className="flex h-9 shrink-0 items-center border-b pr-5 pl-11">
        <Skeleton className="h-3 w-12" />
      </div>
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="flex h-11 shrink-0 items-center border-b pr-5 pl-5 max-md:h-14">
          <div className="flex min-w-0 flex-1 items-center gap-3 px-2">
            <Skeleton className="size-4 shrink-0 rounded-sm" />
            <Skeleton className={cn("h-3.5", TITLE_WIDTHS[index % TITLE_WIDTHS.length])} />
          </div>
          <div className="flex w-44 shrink-0 items-center px-2 max-xl:hidden">
            <Skeleton className="h-3.5 w-28" />
          </div>
          <div className="flex w-24 shrink-0 items-center px-2 max-md:hidden">
            <Skeleton className="size-4 rounded-sm" />
          </div>
          <div className="flex w-36 shrink-0 items-center gap-1.5 px-2 max-lg:hidden">
            <Skeleton className="size-5 rounded-full" />
            <Skeleton className="h-3.5 w-16" />
          </div>
          <div className="flex w-28 shrink-0 items-center px-2 max-lg:hidden">
            <Skeleton className="h-3.5 w-16" />
          </div>
          <div className="flex w-16 shrink-0 items-center justify-end px-2 max-md:hidden">
            <Skeleton className="h-3.5 w-6" />
          </div>
          <div className="flex w-24 shrink-0 items-center gap-1.5 px-2">
            <Skeleton className="size-4 rounded-full" />
            <Skeleton className="h-3.5 w-8" />
          </div>
        </div>
      ))}
    </SkeletonFill>
  );
}

/** A titled block of a settings page (SettingsSection): the title and its description. */
export function SectionHeadingSkeleton() {
  return (
    <div className="flex flex-col gap-1">
      <TextLine lineHeight="h-[1.375rem]" bar="h-4" className="w-40" />
      <TextLine lineHeight="h-5" className="w-72" />
    </div>
  );
}

/** Labelled inputs, one under the other: a form that has not arrived yet. */
export function FieldsSkeleton({ count = 3 }: { count?: number }) {
  return (
    <div className="flex flex-col gap-5">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className="flex flex-col gap-2">
          <TextLine className="w-24" />
          <Skeleton className="h-9 w-full" />
        </div>
      ))}
      <Skeleton className="h-9 w-24" />
    </div>
  );
}

/** A settings page's block with a form in it. */
export function SettingsSectionSkeleton({ fields = 3 }: { fields?: number }) {
  return (
    <div aria-hidden="true" className="flex flex-col gap-6">
      <SectionHeadingSkeleton />
      <FieldsSkeleton count={fields} />
    </div>
  );
}

/** A settings page's block with a list of lines in it (rows of a list, or a table). */
export function SettingsListSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div aria-hidden="true" className="flex flex-col gap-6">
      <SectionHeadingSkeleton />
      <div className="flex flex-col gap-2">
        <Skeleton className="h-9 w-full" />
        {Array.from({ length: rows }, (_, index) => (
          <div key={index} className="flex h-12 items-center gap-3 rounded-lg border px-3">
            <Skeleton className="size-7 shrink-0 rounded-full" />
            <div className="flex min-w-0 flex-1 flex-col gap-1.5">
              <Skeleton className={cn("h-3.5", TITLE_WIDTHS[index % TITLE_WIDTHS.length])} />
            </div>
            <Skeleton className="h-5 w-14 shrink-0 rounded-full" />
          </div>
        ))}
      </div>
    </div>
  );
}

/** A settings page's block that is a stack of collapsed cards (SettingsCard): an icon, a name and what it does, a badge. */
export function SettingsCardsSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div aria-hidden="true" className="flex flex-col gap-6">
      <SectionHeadingSkeleton />
      <div className="flex flex-col gap-3">
        {Array.from({ length: count }, (_, index) => (
          <div key={index} className="flex items-center gap-3 rounded-xl border bg-card px-4 py-3">
            <Skeleton className="size-9 shrink-0 rounded-lg" />
            <div className="flex min-w-0 flex-1 flex-col">
              <TextLine lineHeight="h-[1.375rem]" bar="h-4" className="w-32" />
              <TextLine lineHeight="h-5" className="w-64" />
            </div>
            <Skeleton className="h-5 w-12 shrink-0 rounded-full" />
            <Skeleton className="size-4 shrink-0" />
          </div>
        ))}
      </div>
    </div>
  );
}

/** A card in a page's right-hand column (PropertyCard): a title, then "name: value" lines. */
export function PropertyCardSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div className="flex flex-col gap-1 rounded-xl border bg-card px-3 py-2.5">
      <div className="flex min-h-7 items-center px-1">
        <Skeleton className="h-3.5 w-16" />
      </div>
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="flex min-h-8 items-center gap-2 px-1">
          <Skeleton className="h-3.5 w-20 shrink-0" />
          <span className="w-8 shrink-0" />
          <Skeleton className={cn("h-3.5", index % 2 === 0 ? "w-24" : "w-16")} />
        </div>
      ))}
    </div>
  );
}

/** A few paragraphs of a document: lines of text, with a shorter one at the end of each paragraph. */
export function ParagraphsSkeleton({ paragraphs = 2 }: { paragraphs?: number }) {
  return (
    <div className="flex flex-col gap-4">
      {Array.from({ length: paragraphs }, (_, index) => (
        <div key={index} className="flex flex-col">
          <TextLine lineHeight="h-6" className="w-full" />
          <TextLine lineHeight="h-6" className="w-11/12" />
          <TextLine lineHeight="h-6" className="w-2/3" />
        </div>
      ))}
    </div>
  );
}

/** The activity under an issue, a project or a document: the heading, the box for a comment and a few events. */
export function ActivitySkeleton({ events = 3 }: { events?: number }) {
  return (
    <div aria-hidden="true" className="flex flex-col gap-3 border-t pt-5">
      <div className="flex h-8 items-center">
        <Skeleton className="h-4 w-16" />
      </div>
      <Skeleton className="h-24 w-full rounded-lg" />
      <div className="flex flex-col gap-2.5">
        {Array.from({ length: events }, (_, index) => (
          <div key={index} className="flex items-center gap-2.5">
            <Skeleton className="size-6 shrink-0 rounded-full" />
            <Skeleton className={cn("h-3.5", index % 2 === 0 ? "w-2/3" : "w-1/2")} />
          </div>
        ))}
      </div>
    </div>
  );
}

/** The dialog of an issue: what it says on the left, its properties on the right (IssueDetail). */
export function IssueDetailSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-1 flex-col gap-8 p-4 pt-1 sm:p-5 sm:pt-1 @3xl:flex-row @3xl:items-start @3xl:gap-6">
      <div className="flex w-full min-w-0 flex-1 flex-col gap-5 px-1">
        <TextLine lineHeight="h-9" bar="h-6" className="w-2/3" />
        <ParagraphsSkeleton paragraphs={2} />
        <div className="flex items-center gap-2">
          <Skeleton className="h-7 w-14 rounded-full" />
          <Skeleton className="h-7 w-14 rounded-full" />
        </div>
        <ActivitySkeleton />
      </div>
      <div className="flex w-full shrink-0 flex-col gap-6 px-2 @3xl:w-64 @3xl:pt-2">
        <div className="flex flex-col gap-1">
          {Array.from({ length: 6 }, (_, index) => (
            <div key={index} className="grid min-h-8 grid-cols-[minmax(0,35fr)_minmax(0,65fr)] items-center gap-3">
              <Skeleton className="h-3.5 w-16" />
              <Skeleton className={cn("h-6 rounded-full", index % 2 === 0 ? "w-24" : "w-20")} />
            </div>
          ))}
        </div>
        <div className="flex flex-col gap-1">
          <TextLine className="w-16" />
          <div className="flex min-h-8 items-center gap-2">
            <Skeleton className="size-6 rounded-full" />
            <Skeleton className="h-3.5 w-24" />
          </div>
        </div>
      </div>
    </div>
  );
}

/** A project's overview (ProjectOverview): the name and the description in the middle, cards at the right. */
export function ProjectOverviewSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-1 flex-col gap-8 px-4 pt-2 pb-10 lg:flex-row lg:items-start lg:gap-6">
      <div className="mx-auto flex w-full max-w-3xl min-w-0 flex-1 flex-col gap-6 px-2 pt-8 sm:px-6 lg:pt-14">
        <div className="flex flex-col gap-3">
          <Skeleton className="size-9 rounded-lg" />
          <TextLine lineHeight="h-8" bar="h-6" className="w-64" />
        </div>
        <ParagraphsSkeleton paragraphs={2} />
        <Skeleton className="h-36 w-full rounded-xl" />
        <ActivitySkeleton />
      </div>
      <div className="mx-auto flex w-full max-w-3xl shrink-0 flex-col gap-2 lg:mx-0 lg:w-80">
        <PropertyCardSkeleton rows={8} />
        <PropertyCardSkeleton rows={3} />
      </div>
    </div>
  );
}

/** The sections of a project's settings (ProjectSettingsSections): the index at the left, then block after block of forms. */
export function ProjectSettingsBodySkeleton({ blocks = 4 }: { blocks?: number }) {
  return (
    <div aria-hidden="true" className="grid min-w-0 items-start gap-6 lg:grid-cols-[10rem_minmax(0,1fr)]">
      <div className="flex flex-wrap gap-1 rounded-lg border p-2 lg:flex-col">
        {Array.from({ length: 9 }, (_, index) => (
          <div key={index} className="flex h-9 items-center px-3">
            <Skeleton className={cn("h-3.5", index % 2 === 0 ? "w-16" : "w-20")} />
          </div>
        ))}
      </div>
      <div className="flex min-w-0 flex-col gap-10">
        {Array.from({ length: blocks }, (_, index) => (
          <SettingsSectionSkeleton key={index} fields={index === 0 ? 4 : 2} />
        ))}
      </div>
    </div>
  );
}

/** A document under its header (DocumentEditor): the title, then the body. */
export function DocumentSkeleton() {
  return (
    <div aria-hidden="true" className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-6 pt-6 pb-16">
      <div className="flex h-6 items-center justify-end" />
      <TextLine lineHeight="h-[1.875rem]" bar="h-6" className="w-1/2" />
      <div className="min-h-64">
        <ParagraphsSkeleton paragraphs={3} />
      </div>
    </div>
  );
}

/** The list of all projects, or the archived ones: the header, the two tabs and the table. */
export function ProjectListPageSkeleton({ actions = true }: { actions?: boolean }) {
  return (
    <>
      <HeaderSkeleton crumbs={["w-20"]} actions={actions} />
      <TabsSkeleton count={2} />
      <ProjectTableSkeleton />
    </>
  );
}

/** The projects and the chosen one's settings, side by side (관리자 설정 > 프로젝트). */
export function AdminProjectsSkeleton() {
  return (
    // data-wide: the administrator layout gives this page a wider column (admin/layout.tsx).
    <div data-wide aria-hidden="true" className="flex flex-col gap-6">
      <SectionHeadingSkeleton />
      <div className="grid items-start gap-6 md:grid-cols-[17rem_minmax(0,1fr)]">
        <div className="flex flex-col gap-2">
          <Skeleton className="h-9 w-full max-md:h-11" />
          <TextLine className="w-16" />
          <div className="flex flex-col gap-0.5 rounded-xl border p-1">
            {Array.from({ length: 8 }, (_, index) => (
              <div key={index} className="flex h-9 items-center gap-2 px-2">
                <Skeleton className="size-4 shrink-0 rounded-sm" />
                <Skeleton className={cn("h-3.5", index % 2 === 0 ? "w-28" : "w-20")} />
              </div>
            ))}
          </div>
        </div>
        <div className="flex min-w-0 flex-col gap-8 rounded-xl border bg-card px-5 py-6">
          <div className="flex items-center gap-3">
            <Skeleton className="size-9 shrink-0 rounded-lg" />
            <div className="flex flex-col">
              <TextLine lineHeight="h-[1.375rem]" bar="h-4" className="w-32" />
              <TextLine className="w-20" />
            </div>
          </div>
          <ProjectSettingsBodySkeleton blocks={3} />
        </div>
      </div>
    </div>
  );
}

/** What a search page holds under its header: the box for the words, the kinds, and the hits. */
export function SearchBodySkeleton() {
  return (
    <div aria-hidden="true" className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-6 sm:px-6">
      <Skeleton className="h-11 w-full rounded-lg" />
      <div className="flex gap-1 border-b">
        {["w-8", "w-10", "w-12", "w-12"].map((width, index) => (
          <div key={index} className="flex h-9 items-center px-2.5 max-md:h-11">
            <Skeleton className={cn("h-3.5", width)} />
          </div>
        ))}
      </div>
      <div className="flex flex-col">
        {Array.from({ length: 6 }, (_, index) => (
          <div key={index} className="flex h-11 items-center gap-3 px-3">
            <Skeleton className="size-4 shrink-0 rounded-full" />
            <Skeleton className="h-3 w-12 shrink-0" />
            <Skeleton className={cn("h-3.5", TITLE_WIDTHS[index % TITLE_WIDTHS.length])} />
          </div>
        ))}
      </div>
    </div>
  );
}

/** The fields of a sign-in or set-up form (AuthShell holds the title above them) and its button. */
export function AuthFormSkeleton({ fields = 2 }: { fields?: number }) {
  return (
    <div aria-hidden="true" className="flex flex-col gap-5">
      {Array.from({ length: fields }, (_, index) => (
        <div key={index} className="flex flex-col gap-2">
          <TextLine className="w-16" />
          <Skeleton className="h-10 w-full max-md:h-11" />
        </div>
      ))}
      <Skeleton className="h-10 w-full max-md:h-11" />
    </div>
  );
}

/** What a shared page (a link opened by someone who may not be signed in) holds while it is read. */
export function SharedContentSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-4 px-4 pt-6">
      <TextLine lineHeight="h-9" bar="h-6" className="w-1/2" />
      <ParagraphsSkeleton paragraphs={2} />
      <div className="flex flex-col">
        {Array.from({ length: 8 }, (_, index) => (
          <IssueLineSkeleton key={index} width={TITLE_WIDTHS[index % TITLE_WIDTHS.length]} />
        ))}
      </div>
    </div>
  );
}

/**
 * The lines of a list a settings form keeps (automation rules, project fields): a name over a few words about it,
 * and a button or two at the right. `label` is what a screen reader hears while they are read.
 */
export function ListRowsSkeleton({ rows = 3, label }: { rows?: number; label: string }) {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-2">
      <span className="sr-only">{label}</span>
      <div aria-hidden="true" className="flex flex-col gap-2">
        {Array.from({ length: rows }, (_, index) => (
          <div key={index} className="flex items-center gap-2 rounded-lg border p-3">
            <div className="flex min-w-0 flex-1 flex-col">
              <TextLine lineHeight="h-5" bar="h-3.5" className={index % 2 === 0 ? "w-40" : "w-32"} />
              <TextLine lineHeight="h-4" bar="h-3" className="w-64" />
            </div>
            <Skeleton className="size-7 shrink-0" />
          </div>
        ))}
      </div>
    </div>
  );
}
