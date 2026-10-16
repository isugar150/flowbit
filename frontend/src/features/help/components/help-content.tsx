import { HELP_GROUPS } from "@/features/help/content";
import { messages } from "@/messages";
import { HelpBlocks } from "./help-blocks";
import { HelpToc } from "./help-toc";

const m = messages.help;

/**
 * The help page under its header (docs/features/help.md): the table of contents and every
 * section. The part on the administrator settings is for administrators only, since nobody
 * else can open those screens.
 */
export function HelpContent({ isAdmin }: { isAdmin: boolean }) {
  const groups = HELP_GROUPS.filter((group) => !group.adminOnly || isAdmin);

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-10 px-6 py-10 lg:flex-row lg:items-start">
      <aside className="lg:sticky lg:top-14 lg:max-h-[calc(100dvh-6rem)] lg:w-52 lg:shrink-0 lg:overflow-y-auto">
        <HelpToc
          groups={groups.map((group) => ({
            id: group.id,
            title: group.title,
            sections: group.sections.map(({ id, title }) => ({ id, title })),
          }))}
        />
      </aside>
      <div className="flex min-w-0 max-w-[45rem] flex-1 flex-col gap-14">
        <p className="text-muted-foreground">{m.intro}</p>
        {groups.map((group) => (
          <section key={group.id} aria-labelledby={group.id} className="flex flex-col gap-10">
            <div className="flex flex-col gap-1 border-b pb-3">
              <h2 id={group.id} className="scroll-mt-14 text-xl font-semibold tracking-[-0.01em]">
                {group.title}
              </h2>
              <p className="text-[0.8125rem] leading-5 text-muted-foreground">{group.description}</p>
            </div>
            {group.sections.map((section) => (
              <section
                key={section.id}
                id={section.id}
                aria-labelledby={`${section.id}-title`}
                className="flex scroll-mt-14 flex-col gap-4"
              >
                <h3 id={`${section.id}-title`} className="text-[0.9375rem] leading-[1.375rem] font-semibold">
                  <a
                    href={`#${section.id}`}
                    className="rounded-sm outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
                  >
                    {section.title}
                  </a>
                </h3>
                <HelpBlocks blocks={section.blocks} />
              </section>
            ))}
          </section>
        ))}
      </div>
    </div>
  );
}
