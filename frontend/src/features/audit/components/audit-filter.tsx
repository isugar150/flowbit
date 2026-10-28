import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { messages } from "@/messages";
import type { AuditQuery } from "../list-query";

const m = messages.audit;

/**
 * What narrows the audit log: the kind, who did it, and the days. A plain GET form, so the choice
 * lands in the address and the page needs no script to filter.
 */
export function AuditFilter({ query, types }: { query: AuditQuery; types: string[] }) {
  const filtered = query.type || query.actor || query.from || query.to;
  return (
    <form method="get" action="/admin/audit" className="flex flex-wrap items-end gap-3">
      <label className="flex flex-col gap-1 text-[0.8125rem] text-muted-foreground">
        {m.typeLabel}
        <NativeSelect name="type" defaultValue={query.type} className="max-md:w-full">
          <NativeSelectOption value="">{m.allTypes}</NativeSelectOption>
          {types.map((type) => (
            <NativeSelectOption key={type} value={type}>
              {m.types[type] ?? type}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      </label>
      <label className="flex flex-col gap-1 text-[0.8125rem] text-muted-foreground">
        {m.actorLabel}
        <Input name="actor" defaultValue={query.actor} placeholder={m.actorPlaceholder} maxLength={64} className="h-8 w-44 max-md:w-full" />
      </label>
      <label className="flex flex-col gap-1 text-[0.8125rem] text-muted-foreground">
        {m.fromLabel}
        <Input type="date" name="from" defaultValue={query.from} className="h-8 w-40" />
      </label>
      <label className="flex flex-col gap-1 text-[0.8125rem] text-muted-foreground">
        {m.toLabel}
        <Input type="date" name="to" defaultValue={query.to} className="h-8 w-40" />
      </label>
      <Button type="submit" variant="outline" className="h-8 max-md:h-11">
        {m.apply}
      </Button>
      {filtered ? (
        <Link href="/admin/audit" prefetch={false} className="flex h-8 items-center text-[0.8125rem] text-muted-foreground underline-offset-4 hover:underline max-md:h-11">
          {m.reset}
        </Link>
      ) : null}
    </form>
  );
}
