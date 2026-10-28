"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { messages } from "@/messages";
import { cycleSpan, daysLeft, getCycles } from "../api";

const m = messages.cycles;

/** The cycle that is running on the project's overview: its days, how many days are left and how much of it is done. */
export function ProjectCycleCard({ projectId }: { projectId: string }) {
  const cycles = useQuery({ queryKey: ["cycles", projectId], queryFn: () => getCycles(projectId), staleTime: 30_000 });
  const data = cycles.data;
  if (!data?.settings.enabled || !data.current) return null;
  const cycle = data.current;
  const percent = cycle.total === 0 ? 0 : Math.round((cycle.done / cycle.total) * 100);

  return (
    <section className="flex flex-col gap-2" aria-labelledby="project-cycle-title">
      <div className="flex items-center justify-between gap-3">
        <h3 id="project-cycle-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
          {m.cardTitle(cycle.number)}
        </h3>
        <Link
          href={`/issues?project=${encodeURIComponent(projectId)}&cycle=current&view=board`}
          className="rounded-md px-1.5 text-[0.8125rem] leading-6 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          {m.openBoard}
        </Link>
      </div>
      <div className="flex items-center gap-3 text-[0.8125rem] leading-5">
        <span className="tabular-nums">{cycleSpan(cycle)}</span>
        <span className="text-muted-foreground">{m.daysLeft(daysLeft(cycle, data.today))}</span>
        <span className="ml-auto tabular-nums text-muted-foreground">{m.progress(cycle.done, cycle.total, percent)}</span>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100} aria-label={m.cardTitle(cycle.number)}>
        <div className="h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
      </div>
    </section>
  );
}
