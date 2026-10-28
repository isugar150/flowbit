"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { ChevronRightIcon } from "lucide-react";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { ConfirmDiscardDialog } from "@/components/confirm-discard-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { ShortcutKeys } from "@/components/shortcut-keys";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { DraftProjectFields } from "@/features/project-fields/draft-project-fields";
import { getProjectFields, projectFieldsKey } from "@/features/project-fields/api";
import type { CustomPropertyValue } from "@/features/custom-properties/types";
import { DraftProperties } from "@/features/custom-properties/components/draft-properties";
import { draftsToInput, draftWithBadNumber, withoutPeople, type DraftProperty } from "@/features/custom-properties/drafts";
import type { UserSummary } from "@/features/members/types";
import { mentionablesOf, usersAsMembers, type ProjectMember, type ProjectSummary } from "@/features/projects/types";
import { apiFetch } from "@/lib/api/client";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createIssue } from "../api";
import type { Issue, Label, NewIssueInput } from "../types";
import { IssuePropertyFields } from "./issue-properties";
import { NewIssueRelations, type DraftRelation } from "./new-issue-relations";

const m = messages.issues;

type Properties = Omit<NewIssueInput, "title" | "content">;
const DEFAULT_PROPERTIES: Properties = {
  status: "BACKLOG",
  priority: "NONE",
  assigneeIds: [],
  startDate: null,
  dueDate: null,
  labelIds: [],
  projectId: null,
};

/**
 * New issue: a title and a body on the left, the properties in a column on the right (below on
 * a narrow screen), the same arrangement as the issue page. Only the title is required.
 * Ctrl/⌘+Enter creates it from anywhere in the form.
 */
export function NewIssueDialog({
  open,
  onOpenChange,
  projects,
  defaultProjectId,
  initialProjectId = null,
  members: defaultMembers,
  labels,
  defaultAssigneeId = null,
  defaultDates = null,
  onCreated,
  openCreated = true,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Projects the issue can be linked to. */
  projects: ProjectSummary[];
  /** The project the dialog was opened in; null on the list of every issue. */
  defaultProjectId: string | null;
  /** A project to start with that `members` is not for; they are fetched. */
  initialProjectId?: string | null;
  /** Who can be assigned, for `defaultProjectId` (everyone when it is null). */
  members: ProjectMember[];
  /** Every label in the workspace. */
  labels: Label[];
  /** Pre-selected on a list of one person's issues ("내 티켓"), so the new issue shows up there. */
  defaultAssigneeId?: string | null;
  /** Dates to start with, when the dialog is opened from a day or a span of days on the calendar. */
  defaultDates?: { startDate: string | null; dueDate: string } | null;
  /** Told about the ticket that was made, so the list behind can make sure it shows. */
  onCreated?: (issue: Issue) => void;
  /**
   * Whether making a ticket (without "계속 만들기") goes on to open it (D-194). Off where the ticket is an
   * example that has no page, such as the tour's.
   */
  openCreated?: boolean;
}) {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [properties, setProperties] = useState<Properties>(DEFAULT_PROPERTIES);
  // What people add to the ticket on their own (D-193): kept here and sent with the ticket, since it has no id to save them under yet.
  const [drafts, setDrafts] = useState<DraftProperty[]>([]);
  const [projectValues, setProjectValues] = useState<Record<string, CustomPropertyValue>>({});
  const [draftProblem, setDraftProblem] = useState<string | null>(null);
  // The relations to other tickets, kept here and made together with the ticket (D-237).
  const [relations, setRelations] = useState<DraftRelation[]>([]);
  const changeDrafts = (next: DraftProperty[]) => {
    setDrafts(next);
    setDraftProblem(null);
  };
  // The body lives in the editor; a new `revision` restarts it empty (after "create more").
  const [revision, setRevision] = useState(0);
  const content = useRef<RichDoc | null>(null);
  const [createMore, setCreateMore] = useState(false);
  // A manager of a project that uses triage picks whether the ticket goes through the queue (D-236); off, it goes straight onto the list.
  const [sendToTriage, setSendToTriage] = useState(false);
  const [titleError, setTitleError] = useState(false);
  const [lastCreated, setLastCreated] = useState<string | null>(null);
  // Closing with a title or a body that was not made into a ticket asks first (D-182).
  const [hasBody, setHasBody] = useState(false);
  // A property was picked or added since the dialog opened or the last ticket was made: that is something to lose as well (D-182).
  const [touched, setTouched] = useState(false);
  const [asking, setAsking] = useState(false);
  const titleRef = useRef<HTMLTextAreaElement>(null);
  // The title wraps instead of scrolling sideways: the field grows with what is typed.
  useEffect(() => {
    const field = titleRef.current;
    if (!field) return;
    field.style.height = "auto";
    field.style.height = `${field.scrollHeight}px`;
  }, [title, open]);

  const resetBody = () => {
    content.current = null;
    setHasBody(false);
    setRevision((current) => current + 1);
  };

  // Every time the dialog opens (button, `C`), start from the list's project and
  // without the previous attempt's messages. Adjusted while rendering, since the dialog is opened
  // from outside and its own onOpenChange does not run for that.
  // Starts as closed even when the dialog is first rendered open (it is loaded on first use), so
  // that first opening takes the defaults too.
  const [wasOpen, setWasOpen] = useState(false);
  if (open !== wasOpen) {
    setWasOpen(open);
    if (open) {
      setProperties((current) => ({
        ...current,
        projectId: initialProjectId ?? defaultProjectId,
        assigneeIds: current.assigneeIds.length > 0 || !defaultAssigneeId ? current.assigneeIds : [defaultAssigneeId],
        startDate: defaultDates?.startDate ?? null,
        dueDate: defaultDates?.dueDate ?? null,
      }));
      // A person property names a member of the project, and the project starts over, so the properties do too.
      changeDrafts([]);
      setProjectValues({});
      setRelations([]);
      setTouched(false);
      setTitleError(false);
      setLastCreated(null);
      // Each time the dialog opens the ticket starts as one that goes straight in; "계속 만들기" keeps the pick between tickets.
      setSendToTriage(false);
    }
  }

  const mutation = useRefreshingMutation(createIssue, (issue) => {
    onCreated?.(issue);
    setTitle("");
    // Relations belong to the one ticket they were made with: the next one starts without.
    setRelations([]);
    // What was kept for the next ticket ("계속 만들기") was made; closing now loses nothing.
    setTouched(false);
    if (createMore) {
      // Keep what the next issue is likely to share (status, labels, assignees); start a fresh body.
      resetBody();
      setLastCreated(issue.key);
      titleRef.current?.focus();
    } else {
      setProperties({
        ...DEFAULT_PROPERTIES,
        projectId: initialProjectId ?? defaultProjectId,
        assigneeIds: defaultAssigneeId ? [defaultAssigneeId] : [],
      });
      changeDrafts([]);
      resetBody();
      onOpenChange(false);
      // Straight to the ticket that was just made, over the list, as when a line of it is clicked.
      if (openCreated) router.push(`/issue/${issue.key}`);
    }
  });

  // Who can be assigned follows the chosen project. For the project the
  // dialog was opened in they came with the page; for another one they are fetched.
  const projectId = properties.projectId;
  const projectFields = useQuery({ queryKey: projectFieldsKey(projectId), queryFn: () => getProjectFields(projectId as string), enabled: open && !!projectId });
  const other = projectId !== defaultProjectId;
  const fetched = useQuery({
    queryKey: ["new-issue-project", projectId],
    enabled: open && other,
    queryFn: async (): Promise<ProjectMember[]> => {
      if (projectId === null) return usersAsMembers(await apiFetch<UserSummary[]>("/api/v1/users"));
      return apiFetch<ProjectMember[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/members`);
    },
  });
  const members = other ? (fetched.data ?? []) : defaultMembers;
  // Only a manager has the choice: what anyone else makes in a project that uses triage always goes through the queue.
  const chosenProject = projects.find((project) => project.id === projectId);
  const canChooseTriage = chosenProject?.triageEnabled === true && chosenProject.myRole === "MANAGER";
  // Assignees belong to a project; changing the project starts those over.
  const setProject = (key: string | null) => {
    // The person the list is for (D-195) stays while they are still picked: they may make a ticket in this project, so they can be given it.
    setProperties((current) => ({
      ...current,
      projectId: key,
      assigneeIds: defaultAssigneeId && current.assigneeIds.includes(defaultAssigneeId) ? [defaultAssigneeId] : [],
    }));
    // The same for the people named in the added properties: they were members of the other project.
    setDrafts(withoutPeople);
    setProjectValues({});
  };

  const submit = () => {
    if (mutation.isPending) return;
    if (!title.trim()) {
      setTitleError(true);
      titleRef.current?.focus();
      return;
    }
    const notANumber = draftWithBadNumber(drafts);
    if (notANumber) {
      setDraftProblem(messages.customProperties.notANumberOf(notANumber.label));
      return;
    }
    setLastCreated(null);
    mutation.mutate({
      title,
      content: content.current,
      ...properties,
      ...(drafts.length > 0 ? { customProperties: draftsToInput(drafts) } : null),
      projectFields: Object.entries(projectValues).map(([fieldId, value]) => ({ fieldId, value })),
      ...(relations.length > 0 ? { relations: relations.map((relation) => ({ kind: relation.kind, targetNumber: relation.target.number })) } : null),
      ...(canChooseTriage && sendToTriage ? { triage: true } : null),
    });
  };

  const startAgain = () => {
    setTitle("");
    setTouched(false);
    changeDrafts([]);
    setRelations([]);
    resetBody();
    setProperties({
      ...DEFAULT_PROPERTIES,
      projectId: initialProjectId ?? defaultProjectId,
      assigneeIds: defaultAssigneeId ? [defaultAssigneeId] : [],
    });
  };
  // Closing (the X, Esc, a click outside) with something typed asks first; "닫고 내용 버리기" starts the next one empty.
  const dirty = title.trim() !== "" || hasBody || touched;
  const requestOpenChange = (next: boolean) => {
    if (next || !dirty) {
      if (!next) setAsking(false);
      onOpenChange(next);
      return;
    }
    if (!mutation.isPending) setAsking(true);
  };

  const resetMutation = mutation.reset;
  useEffect(() => {
    if (open) resetMutation();
  }, [open, resetMutation]);

  return (
    <Dialog open={open} onOpenChange={requestOpenChange}>
      {/* The dialog exists to type a title: put the cursor there as it opens. */}
      <DialogContent className="@container max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] gap-0 overflow-y-auto overscroll-contain p-0 sm:max-w-5xl xl:max-w-[min(84rem,calc(100vw-6rem))]" initialFocus={titleRef}>
        <DialogTitle className="sr-only">{m.newTitle}</DialogTitle>
        <DialogDescription className="sr-only">{m.emptyDescription}</DialogDescription>
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing) {
              event.preventDefault();
              submit();
            }
          }}
          className="flex flex-col"
        >
          <div className="flex items-center gap-1 px-4 pt-3.5 text-[0.8125rem] leading-5">
            {projectId ? (
              <>
                {/* The name is cut at its end; the badge alone would centre it and lose both ends. */}
                <Badge variant="secondary" className="max-w-48 justify-start">
                  <span className="truncate">{projects.find((project) => project.id === projectId)?.name ?? projectId}</span>
                </Badge>
                <ChevronRightIcon className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </>
            ) : null}
            <span className="font-medium">{m.new}</span>
          </div>
          <div className="flex flex-col @min-[35rem]:flex-row">
          <div className="flex min-w-0 flex-1 flex-col gap-3 px-5 pt-4 pb-5">
            <textarea
              ref={titleRef}
              rows={1}
              value={title}
              placeholder={m.titlePlaceholder}
              aria-label={m.titleLabel}
              aria-invalid={titleError ? true : undefined}
              aria-describedby={titleError ? "new-issue-title-error" : undefined}
              maxLength={200}
              autoComplete="off"
              onChange={(event) => {
                // A title is one line however it was typed or pasted.
                setTitle(event.target.value.replace(/\s*\n\s*/g, " "));
                setTitleError(false);
              }}
              onKeyDown={(event) => {
                // Plain Enter must not create a half-written issue.
                if (event.key === "Enter" && !event.ctrlKey && !event.metaKey) event.preventDefault();
              }}
              className="max-h-40 w-full resize-none bg-transparent text-2xl leading-8 font-semibold outline-none placeholder:font-medium placeholder:text-muted-foreground"
            />
            {titleError ? (
              <p id="new-issue-title-error" role="alert" className="text-[0.8125rem] leading-5 text-destructive">
                {m.titleRequired}
              </p>
            ) : null}
            <RichTextEditor
              key={revision}
              initialContent={null}
              placeholder={m.descriptionPlaceholder}
              ariaLabel={m.descriptionLabel}
              mentionables={mentionablesOf(members)}
              // -ml/pl: the scroll box reaches into the left padding so the block grip is not clipped.
              className="-ml-4 max-h-[55dvh] min-h-[var(--new-issue-body-height)] overflow-y-auto overscroll-contain pl-4 [--new-issue-body-height:min(20rem,45dvh)] @min-[35rem]:[--new-issue-body-height:min(28rem,55dvh)] [&_.tiptap]:min-h-[var(--new-issue-body-height)]"
              onChange={(doc) => {
                content.current = doc;
                setHasBody(doc !== null);
              }}
            />
          </div>
          {/* The same panel as on an existing issue, so making and changing look alike. */}
          <aside className="min-w-0 shrink-0 border-t px-5 py-4 @min-[35rem]:w-64 @min-[35rem]:border-t-0 @min-[35rem]:border-l">
            <IssuePropertyFields
              values={properties}
              onChange={(changes) => {
                setTouched(true);
                // Assignees belong to a project; changing the project starts those over.
                if ("projectId" in changes) setProject(changes.projectId ?? null);
                else setProperties((current) => ({ ...current, ...changes }));
              }}
              members={members}
              labels={labels}
              projects={projects}
              afterDueDate={
                <>
                <DraftProjectFields fields={projectFields.data ?? []} values={projectValues} members={members} onChange={(fieldId, value) => { setProjectValues((current) => ({ ...current, [fieldId]: value })); setTouched(true); }} />
                <DraftProperties
                  reservedLabels={projectFields.data?.map((field) => field.label)}
                  drafts={drafts}
                  onChange={(next) => {
                    changeDrafts(next);
                    setTouched(true);
                  }}
                  members={members}
                />
                </>
              }
            />
            <NewIssueRelations
              relations={relations}
              onChange={(next) => {
                setRelations(next);
                setTouched(true);
              }}
            />
          </aside>
          </div>
          {draftProblem ? (
            <div className="px-4 pb-2">
              <FormAlert>{draftProblem}</FormAlert>
            </div>
          ) : mutation.isError ? (
            <div className="px-4 pb-2">
              <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert>
            </div>
          ) : null}
          <div className="flex flex-wrap items-center justify-end gap-3 border-t px-4 py-2.5">
            {lastCreated ? (
              <p role="status" className="mr-auto text-[0.8125rem] leading-5 text-muted-foreground">
                {m.created(lastCreated)}
              </p>
            ) : null}
            {canChooseTriage ? (
              <label title={m.sendToTriageHint} className="flex items-center gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={sendToTriage}
                  onChange={(event) => setSendToTriage(event.target.checked)}
                />
                {m.sendToTriage}
              </label>
            ) : null}
            <label className="flex items-center gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
              <input
                type="checkbox"
                className="size-4 accent-primary"
                checked={createMore}
                onChange={(event) => setCreateMore(event.target.checked)}
              />
              {m.createMore}
            </label>
            <Button type="submit" className="max-md:h-11" disabled={mutation.isPending}>
              {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
              {mutation.isPending ? m.creating : m.create}
              {mutation.isPending ? null : (
                <ShortcutKeys id="submit" />
              )}
            </Button>
          </div>
        </form>
        <ConfirmDiscardDialog
          open={asking}
          onKeep={() => setAsking(false)}
          onDiscard={() => {
            setAsking(false);
            startAgain();
            onOpenChange(false);
          }}
        />
      </DialogContent>
    </Dialog>
  );
}
