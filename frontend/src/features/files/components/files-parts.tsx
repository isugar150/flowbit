import { cn } from "@/lib/utils";

/** A titled block of the file page. `action` sits at the right of the title. */
export function Block({
  title,
  hint,
  action,
  children,
}: {
  title: string;
  hint?: string;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h3 className="text-[0.875rem] leading-5 font-semibold">{title}</h3>
          {hint ? <p className="text-[0.8125rem] leading-5 text-muted-foreground">{hint}</p> : null}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

/** One figure: the size first, the number of files under it. */
export function Stat({ label, size, count, hint }: { label: string; size: string; count: string; hint?: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5 rounded-xl border px-4 py-3">
      <span className="text-[0.8125rem] leading-5 text-muted-foreground">{label}</span>
      <span className="text-[1.375rem] leading-[1.875rem] font-semibold tabular-nums">{size}</span>
      <span className="text-[0.8125rem] leading-5 text-muted-foreground tabular-nums">{count}</span>
      {hint ? <span className="text-xs leading-4 text-muted-foreground">{hint}</span> : null}
    </div>
  );
}

/** A table with a header row; it scrolls sideways inside its frame on a narrow screen. */
export function DataTable({
  caption,
  head,
  children,
}: {
  caption: string;
  head: { label: string; right?: boolean }[];
  children: React.ReactNode;
}) {
  return (
    <div className="overflow-x-auto rounded-xl border">
      <table className="w-full text-[0.8125rem] leading-5">
        <caption className="sr-only">{caption}</caption>
        <thead className="border-b bg-muted/40 text-left text-muted-foreground">
          <tr>
            {head.map((column) => (
              <th key={column.label} scope="col" className={cn("px-3 py-2 font-medium whitespace-nowrap", column.right && "text-right")}>
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y">{children}</tbody>
      </table>
    </div>
  );
}

export function Cell({ children, right, className }: { children: React.ReactNode; right?: boolean; className?: string }) {
  return <td className={cn("px-3 py-2 align-top", right && "text-right tabular-nums whitespace-nowrap", className)}>{children}</td>;
}

/** A line of the check: what it is, what it means, and the figure. `bad` is for what needs the administrator's attention. */
export function Fact({ label, hint, value, bad }: { label: string; hint: string; value: string; bad?: boolean }) {
  return (
    <li className="flex flex-col gap-1 px-4 py-3 sm:flex-row sm:items-start sm:justify-between sm:gap-6">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-[0.875rem] leading-5 font-medium">{label}</span>
        <span className="text-[0.8125rem] leading-5 text-muted-foreground">{hint}</span>
      </div>
      <span className={cn("text-[0.875rem] leading-5 tabular-nums sm:shrink-0 sm:text-right", bad && "font-medium text-destructive")}>{value}</span>
    </li>
  );
}

/** A name that is too long is cut, but can still be read in full where there is room. */
export function Name({ children }: { children: string }) {
  return <span className="block max-w-[22rem] truncate">{children}</span>;
}
