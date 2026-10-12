import { notFound } from "next/navigation";
import { FileTextIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { DocumentEditor } from "@/features/issues/components/document-editor";
import type { IssueDocument } from "@/features/issues/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

/** A document that belongs to an issue: a title and a body, saved as they are typed. */
export default async function DocumentPage({ params }: PageProps<"/doc/[id]">) {
  const { id } = await params;
  if (!/^[0-9a-f-]{36}$/.test(id)) notFound();
  const document = await serverGet<IssueDocument>(`/api/v1/documents/${id}`);
  if (!document) notFound();

  return (
    <>
      <RealtimeScope topics={[`issue:${document.issue.number}`]} presence={`document:${document.id}`} priority={1} />
      <PageHeader
        crumbs={[
          { label: `${document.issue.key} ${document.issue.title}`, href: `/issue/${document.issue.key}` },
          { label: document.title || messages.issues.documents.untitled, icon: <FileTextIcon /> },
        ]}
      />
      <DocumentEditor document={document} />
    </>
  );
}
