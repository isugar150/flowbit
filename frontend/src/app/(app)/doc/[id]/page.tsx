import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { FileTextIcon } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { DocumentEditor } from "@/features/issues/components/document-editor";
import type { IssueDocument } from "@/features/issues/types";
import { RealtimeScope } from "@/features/realtime/realtime-provider";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

/** The document's title in the browser tab. */
export async function generateMetadata({ params }: PageProps<"/doc/[id]">): Promise<Metadata> {
  const { id } = await params;
  if (!/^[0-9a-f-]{36}$/.test(id)) return {};
  const document = await serverGet<IssueDocument>(`/api/v1/documents/${id}`);
  return document ? { title: document.title || messages.issues.documents.untitled } : {};
}

/** A document that belongs to an issue or to a project: a title and a body, saved as they are typed. */
export default async function DocumentPage({ params }: PageProps<"/doc/[id]">) {
  const { id } = await params;
  if (!/^[0-9a-f-]{36}$/.test(id)) notFound();
  const document = await serverGet<IssueDocument>(`/api/v1/documents/${id}`);
  if (!document) notFound();

  return (
    <>
      <RealtimeScope
        topics={[document.issue ? `issue:${document.issue.number}` : `project:${document.project?.id ?? ""}`]}
        presence={`document:${document.id}`}
        priority={1}
      />
      <PageHeader
        crumbs={[
          // Where the document belongs: its issue, or its project.
          document.issue
            ? { label: `${document.issue.key} ${document.issue.title}`, href: `/issue/${document.issue.key}` }
            : { label: document.project?.name ?? "", href: `/project/${document.project?.id ?? ""}` },
          // The documents it sits inside, the outermost first.
          ...document.ancestors.map((ancestor) => ({
            label: ancestor.title || messages.issues.documents.untitled,
            href: `/doc/${ancestor.id}`,
            icon: <FileTextIcon />,
          })),
          { label: document.title || messages.issues.documents.untitled, icon: <FileTextIcon /> },
        ]}
      />
      <DocumentEditor document={document} />
    </>
  );
}
