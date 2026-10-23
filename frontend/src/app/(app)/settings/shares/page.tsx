import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import { MyShareLinks } from "@/features/share/components/my-share-links";
import { ShareListPager, ShareListToolbar } from "@/features/share/components/share-list-nav";
import { SHARES_PAGE_SIZE, pageCount, parseShareListQuery, sharesAddress, sharesApiQuery } from "@/features/share/list-query";
import type { ShareList } from "@/features/share/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.share.mine;

export const metadata: Metadata = { title: m.title };

/**
 * The share links the person made (D-202): a page of them at a time, narrowed to a kind and put in an order the address
 * says (D-204), each with how often it was opened, who opened it, and the delete.
 */
export default async function MyShareLinksPage({ searchParams }: PageProps<"/settings/shares">) {
  const query = parseShareListQuery(await searchParams);
  const list = await serverGet<ShareList>(`/api/v1/shares${sharesApiQuery(query)}`);
  if (!list) notFound();
  // A page past the last one (the links on it were deleted): the last one instead.
  const last = pageCount(list.total, SHARES_PAGE_SIZE);
  if (query.page > last) redirect(sharesAddress(query, { page: last }));
  const all = Object.values(list.kinds).reduce((sum, count) => sum + (count ?? 0), 0);

  return (
    <SettingsSection title={m.title} description={m.description}>
      {all > 0 ? <ShareListToolbar query={query} kinds={list.kinds} /> : null}
      {list.items.length > 0 ? (
        <MyShareLinks links={list.items} />
      ) : (
        <p className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5 text-muted-foreground">{all === 0 ? m.empty : m.emptyKind}</p>
      )}
      <ShareListPager query={query} total={list.total} />
    </SettingsSection>
  );
}
