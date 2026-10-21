"use client";

import { useMemo } from "react";
import { useRouter } from "next/navigation";
import type { MentionPerson } from "@/components/editor/mention";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { SaveStatus } from "@/components/editor/save-status";
import { BlockComments } from "@/features/content/components/block-comments";
import { useContentFeed } from "@/features/content/use-content-feed";
import { IssueKeyMenu } from "@/features/git/components/issue-key-menu";
import type { IssueGitRepository } from "@/features/git/types";
import { useAutosave } from "@/lib/use-autosave";
import { useRemoteValue } from "@/lib/use-remote-value";
import { messages } from "@/messages";
import { createDocument, updateIssue } from "../api";
import type { IssueDetail } from "../types";
import { DeletedDocuments } from "./deleted-documents";

const m = messages.issues;

/**
 * Title and body of one issue, edited in place by people who may edit. There is no edit mode
 * and no save button; what is typed is saved a moment later.
 */
export function IssueBody({
  issue,
  canEdit,
  mentionables,
  branch = null,
  repository = null,
}: {
  issue: IssueDetail;
  canEdit: boolean;
  /** The branch name the key's menu copies; null without Git (D-145). */
  branch?: string | null;
  /** The repository the issue's work is in, which the key's menu opens (D-148). */
  repository?: IssueGitRepository | null;
  /** The people "@" offers in the body. */
  mentionables: MentionPerson[];
}) {
  const router = useRouter();
  const title = useAutosave(async (value: string) => {
    // An emptied title is not saved; the issue keeps its last title.
    if (!value.trim()) return;
    await updateIssue({ number: issue.number, changes: { title: value } });
    // The title also appears in lists that the server rendered.
    router.refresh();
  });
  const content = useAutosave((doc: RichDoc | null) =>
    updateIssue({ number: issue.number, changes: { content: doc } }),
  );
  const status = content.status !== "idle" ? content.status : title.status;
  const titleInput = useRemoteValue<HTMLInputElement>(issue.title, title.isDirty);
  const target = useMemo(() => ({ type: "issue" as const, id: issue.id }), [issue.id]);
  const feed = useContentFeed(target, issue);

  return (
    <>
      <div className="flex flex-col gap-1">
        <div className="flex items-center justify-between gap-3">
          <IssueKeyMenu
            number={issue.number}
            issueKey={issue.key}
            branch={branch}
            repository={repository}
            // An issue on its own has no repositories to pick from (D-162).
            canCreateBranch={canEdit && !issue.deletedAt && issue.project !== null}
          />
          <SaveStatus status={status} />
        </div>
        {canEdit ? (
          <div data-presence-id="title">
          <input
            ref={titleInput}
            defaultValue={issue.title}
            aria-label={m.titleLabel}
            maxLength={200}
            autoComplete="off"
            onChange={(event) => title.schedule(event.target.value)}
            onBlur={() => void title.flush()}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.nativeEvent.isComposing) event.currentTarget.blur();
            }}
            className="block w-full bg-transparent text-[1.375rem] leading-[1.875rem] font-semibold outline-none"
          />
          </div>
        ) : (
          <h2 className="text-[1.375rem] leading-[1.875rem] font-semibold">{issue.title}</h2>
        )}
      </div>
      {canEdit || issue.content ? (
        <div data-presence-id="body">
        <RichTextEditor
          initialContent={issue.content}
          editable={canEdit}
          placeholder={m.bodyPlaceholder}
          ariaLabel={m.descriptionLabel}
          className="min-h-40"
          onChange={content.schedule}
          keepLocal={content.isDirty}
          mentionables={mentionables}
          // Each block can be commented on, by anyone who sees the issue (D-165).
          blockIds
          overlay={({ editor, container }) => (
            <BlockComments
              editor={editor}
              container={container}
              target={target}
              threads={feed.data?.threads ?? []}
              canComment={feed.data?.canComment ?? false}
              mentionables={mentionables}
            />
          )}
          // "/문서" in the body makes a document on this issue and puts it there as a block.
          documents={issue.documents}
          onCreateDocument={
            canEdit
              ? async (title) => {
                  const document = await createDocument({ owner: { issueNumber: issue.number }, title });
                  router.refresh();
                  return { id: document.id, title: document.title };
                }
              : undefined
          }
        />
        </div>
      ) : (
        <p className="text-muted-foreground">{m.noDescription}</p>
      )}
      <DeletedDocuments path={`/api/v1/issues/${issue.number}/documents/deleted`} canEdit={canEdit} />
    </>
  );
}
