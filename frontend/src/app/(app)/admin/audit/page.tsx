import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import { AuditFilter } from "@/features/audit/components/audit-filter";
import { AuditPager, AuditTable } from "@/features/audit/components/audit-table";
import { auditAddress, auditApiQuery, auditPageCount, parseAuditQuery } from "@/features/audit/list-query";
import type { AuditLogPage } from "@/features/audit/types";
import { requireUser, serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.audit;

export const metadata: Metadata = { title: m.title };

/** Security-relevant events, newest first, narrowed by kind, person and day (docs/features/account-security.md). */
export default async function AdminAuditPage({ searchParams }: PageProps<"/admin/audit">) {
  const query = parseAuditQuery(await searchParams);
  const [user, log] = await Promise.all([requireUser(), serverGet<AuditLogPage>(`/api/v1/admin/audit${auditApiQuery(query)}`)]);
  if (!log) notFound();
  // A page past the last one (the filter narrowed the log): the last one instead.
  const last = auditPageCount(log.total);
  if (query.page > last) redirect(auditAddress(query, { page: last }));

  return (
    <SettingsSection title={m.title} description={m.description}>
      <AuditFilter query={query} types={log.types} />
      {log.items.length > 0 ? (
        <AuditTable items={log.items} timeZone={user.timezone} />
      ) : (
        <p className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5 text-muted-foreground">{m.empty}</p>
      )}
      <AuditPager query={query} total={log.total} />
    </SettingsSection>
  );
}
