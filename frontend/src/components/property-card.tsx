/** A titled card in a page's right-hand column, such as "속성" on the project overview. */
export function PropertyCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-1 rounded-xl border bg-card px-3 py-2.5">
      <h2 className="px-1 py-1 text-[0.8125rem] leading-5 font-medium text-muted-foreground">{title}</h2>
      {children}
    </section>
  );
}

/** One label and value inside a PropertyCard. */
export function PropertyRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-h-8 items-center gap-2 px-1 text-[0.8125rem] leading-5">
      <dt className="w-20 shrink-0 text-muted-foreground">{label}</dt>
      <dd className="flex min-w-0 flex-1 items-center gap-2 font-medium">{children}</dd>
    </div>
  );
}
