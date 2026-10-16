import { ArrowRightIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { HELP_GROUPS, HELP_START_HERE, type HelpSection } from "@/features/help/content";
import { messages } from "@/messages";
import { HelpBlocks } from "./help-blocks";
import { HelpToc } from "./help-toc";
import { SectionIcon } from "./section-icon";

const m = messages.help;

/**
 * The help page under its header (docs/features/help.md): the table of contents, a few cards to
 * start from, and every section. The part on the administrator settings is for administrators
 * only, since nobody else can open those screens.
 */
export function HelpContent({ isAdmin }: { isAdmin: boolean }) {
  const groups = HELP_GROUPS.filter((group) => !group.adminOnly || isAdmin);
  const sections = new Map(groups.flatMap((group) => group.sections).map((section) => [section.id, section]));
  const startHere = HELP_START_HERE.flatMap((id) => sections.get(id) ?? []);

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-10 px-6 py-10 lg:flex-row lg:items-start lg:gap-14">
      <aside className="lg:sticky lg:top-14 lg:max-h-[calc(100dvh-6rem)] lg:w-56 lg:shrink-0 lg:overflow-y-auto lg:[scrollbar-width:none]">
        <HelpToc isAdmin={isAdmin} />
      </aside>
      {/* On a narrow screen this box dissolves so that the heading comes before the contents. */}
      <div className="flex min-w-0 max-w-3xl flex-1 flex-col gap-16 max-lg:contents">
        <header className="flex flex-col gap-6 max-lg:-order-1">
          <div className="flex flex-col gap-1.5">
            <p className="text-xl leading-7 font-semibold tracking-[-0.01em]">{m.heading}</p>
            <p className="text-muted-foreground">{m.intro}</p>
          </div>
          <nav aria-label={m.startHere} className="flex flex-col gap-2">
            <p className="text-xs font-medium text-muted-foreground">{m.startHere}</p>
            <ul className="grid grid-cols-2 gap-2">
              {startHere.map((section) => (
                <li key={section.id}>
                  <StartCard section={section} />
                </li>
              ))}
            </ul>
          </nav>
        </header>
        {groups.map((group) => (
          <section key={group.id} aria-labelledby={group.id} className="flex flex-col gap-10">
            <div className="flex flex-col gap-1">
              <div className="flex items-center gap-2">
                <h2 id={group.id} className="scroll-mt-14 text-lg leading-7 font-semibold tracking-[-0.01em]">
                  {group.title}
                </h2>
                {group.adminOnly ? <Badge variant="secondary">{m.adminBadge}</Badge> : null}
              </div>
              <p className="text-[0.8125rem] leading-5 text-muted-foreground">{group.description}</p>
            </div>
            {group.sections.map((section) => (
              <section
                key={section.id}
                id={section.id}
                aria-labelledby={`${section.id}-title`}
                className="flex scroll-mt-14 flex-col gap-5 border-t pt-8"
              >
                <div className="flex items-start gap-3">
                  <SectionIcon icon={section.icon} />
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <h3 id={`${section.id}-title`} className="text-base leading-6 font-semibold tracking-[-0.01em]">
                      <a
                        href={`#${section.id}`}
                        className="rounded-sm outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
                      >
                        {section.title}
                      </a>
                    </h3>
                    <p className="text-[0.8125rem] leading-5 text-muted-foreground">{section.summary}</p>
                  </div>
                </div>
                <HelpBlocks blocks={section.blocks} />
              </section>
            ))}
          </section>
        ))}
      </div>
    </div>
  );
}

function StartCard({ section }: { section: HelpSection }) {
  return (
    <a
      href={`#${section.id}`}
      className="group flex h-full items-start gap-3 rounded-xl border bg-card p-4 outline-none max-sm:items-center max-sm:p-3 hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      <SectionIcon icon={section.icon} />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="font-medium">{section.title}</span>
        <span className="text-[0.8125rem] leading-5 text-muted-foreground max-sm:hidden">{section.summary}</span>
      </span>
      <ArrowRightIcon
        className="mt-0.5 size-4 shrink-0 text-muted-foreground opacity-0 max-sm:hidden group-hover:opacity-100 group-focus-visible:opacity-100"
        aria-hidden="true"
      />
    </a>
  );
}
