/** A titled card in a page's right-hand column, such as "속성" on the project overview. */
export function PropertyCard({
  title,
  action,
  children,
}: {
  title: string;
  /** A small control at the right of the title, such as "+" to add to the card's list. */
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="flex flex-col gap-1 rounded-xl border bg-card px-3 py-2.5">
      <div className="flex min-h-7 items-center justify-between gap-2">
        <h2 className="px-1 py-1 text-[0.8125rem] leading-5 font-medium text-muted-foreground">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

/** One label and value inside a PropertyCard. */
export function PropertyRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    // data-presence-id: others see who is pointing at this row (features/realtime/presence-focus.tsx).
    <div data-presence-id={`property:${label}`} className="flex min-h-8 items-center gap-2 px-1 text-[0.8125rem] leading-5">
      <dt className="w-20 shrink-0 text-muted-foreground">{label}</dt>
      <dd className="flex min-w-0 flex-1 items-center gap-2 font-medium">{children}</dd>
    </div>
  );
}

/** One label and value in a plain list of properties (a milestone's dialog): the label on the left, the value right of it. */
export function PropertyListRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-h-8 items-center gap-4 max-md:min-h-10">
      <dt className="w-16 shrink-0 truncate text-muted-foreground" title={label}>
        {label}
      </dt>
      <dd className="min-w-0 flex-1">{children}</dd>
    </div>
  );
}

/** One label and value on a line of a narrow side panel (an issue's): a shorter gap, as the panel is 16rem wide. */
export function PropertyPanelRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-h-8 items-center gap-3 text-[0.8125rem] leading-5">
      <dt className="w-16 shrink-0 truncate text-muted-foreground" title={label}>
        {label}
      </dt>
      <dd className="min-w-0 flex-1">{children}</dd>
    </div>
  );
}
