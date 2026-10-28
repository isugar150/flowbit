import { Suspense } from "react";
import type { Metadata } from "next";
import { SearchIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { SearchBodySkeleton } from "@/components/skeletons";
import { SearchResults, type SearchTab } from "@/features/search/components/search-results";
import { messages } from "@/messages";

const m = messages.search;

export const metadata: Metadata = { title: m.title };

/**
 * Every issue, document and project matching a query (docs/features/search.md). The header is static; only
 * the part that reads the address waits, inside Suspense, so the page can show at once.
 */
export default function SearchPage({ searchParams }: PageProps<"/search">) {
  return (
    <>
      <PageHeader crumbs={[{ label: m.title, icon: <SearchIcon /> }]} />
      <Suspense fallback={<SearchBodySkeleton />}>
        <SearchBody searchParams={searchParams} />
      </Suspense>
    </>
  );
}

async function SearchBody({ searchParams }: Pick<PageProps<"/search">, "searchParams">) {
  const params = await searchParams;
  const q = typeof params.q === "string" ? params.q : "";
  const tab: SearchTab =
    params.type === "issues" || params.type === "documents" || params.type === "projects" ? params.type : "all";
  return <SearchResults key={q} initialQuery={q} initialTab={tab} />;
}
