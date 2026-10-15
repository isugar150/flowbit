import type { Metadata } from "next";
import { SearchIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { SearchResults, type SearchTab } from "@/features/search/components/search-results";
import { messages } from "@/messages";

const m = messages.search;

export const metadata: Metadata = { title: m.title };

/** Every issue and document matching a query (docs/features/search.md). */
export default async function SearchPage({ searchParams }: PageProps<"/search">) {
  const params = await searchParams;
  const q = typeof params.q === "string" ? params.q : "";
  const tab: SearchTab = params.type === "issues" || params.type === "documents" ? params.type : "all";
  return (
    <>
      <PageHeader crumbs={[{ label: m.title, icon: <SearchIcon /> }]} />
      <SearchResults key={q} initialQuery={q} initialTab={tab} />
    </>
  );
}
