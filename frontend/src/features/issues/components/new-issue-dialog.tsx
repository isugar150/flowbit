"use client";

import { useRef, useState } from "react";
import { ChevronRightIcon } from "lucide-react";
import { RichTextEditor, type RichDoc } from "@/components/editor/rich-text-editor";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Kbd } from "@/components/ui/kbd";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { ProjectMember } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { createIssue } from "../api";
import { PRESET_TEMPLATES } from "../meta";
import { NONE, assigneeOptions, presetOptions, priorityOptions, statusOptions } from "../options";
import type { IssuePreset, NewIssueInput } from "../types";
import { ChipSelect } from "./chip-select";
import { DueDateChip } from "./due-date-chip";

const m = messages.issues;

type Properties = Omit<NewIssueInput, "title" | "content">;
const DEFAULT_PROPERTIES: Properties = { preset: null, status: "BACKLOG", priority: "NONE", assigneeId: null, dueDate: null };

/**
 * New issue: a title, a body, and a row of chips for everything else. Only the title is
 * required. Ctrl/⌘+Enter creates it from anywhere in the form.
 */
export function NewIssueDialog({
  open,
  onOpenChange,
  projectKey,
  members,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  projectKey: string;
  members: ProjectMember[];
}) {
  const [title, setTitle] = useState("");
  const [properties, setProperties] = useState<Properties>(DEFAULT_PROPERTIES);
  // The body lives in the editor. `body` is what the editor starts from; a new `revision`
  // restarts the editor with it (applying a preset, clearing after "create more").
  const [body, setBody] = useState<{ revision: number; initial: RichDoc | null }>({ revision: 0, initial: null });
  const content = useRef<RichDoc | null>(null);
  const bodyTouched = useRef(false);
  const [createMore, setCreateMore] = useState(false);
  const [titleError, setTitleError] = useState(false);
  const [lastCreated, setLastCreated] = useState<string | null>(null);
  const titleRef = useRef<HTMLInputElement>(null);

  const resetBody = (preset: IssuePreset | null) => {
    const initial = preset ? PRESET_TEMPLATES[preset] : null;
    content.current = initial;
    bodyTouched.current = false;
    setBody((current) => ({ revision: current.revision + 1, initial }));
  };

  const mutation = useRefreshingMutation(createIssue, (issue) => {
    setTitle("");
    if (createMore) {
      // Keep what the next issue is likely to share (status, preset, assignee); start a fresh body.
      resetBody(properties.preset);
      setLastCreated(issue.key);
      titleRef.current?.focus();
    } else {
      setProperties(DEFAULT_PROPERTIES);
      resetBody(null);
      onOpenChange(false);
    }
  });

  const set = <K extends keyof Properties>(key: K, value: Properties[K]) =>
    setProperties((current) => ({ ...current, [key]: value }));

  const setPreset = (preset: IssuePreset | null) => {
    set("preset", preset);
    // Lay down the preset's outline unless the person has already written something of their own.
    if (!bodyTouched.current) resetBody(preset);
  };

  const submit = () => {
    if (mutation.isPending) return;
    if (!title.trim()) {
      setTitleError(true);
      titleRef.current?.focus();
      return;
    }
    setLastCreated(null);
    mutation.mutate({ projectKey, issue: { title, content: content.current, ...properties } });
  };

  const handleOpenChange = (next: boolean) => {
    if (next) {
      mutation.reset();
      setTitleError(false);
      setLastCreated(null);
    }
    onOpenChange(next);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      {/* The dialog exists to type a title: put the cursor there as it opens. */}
      <DialogContent className="gap-0 p-0 sm:max-w-2xl" initialFocus={titleRef}>
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
            <Badge variant="secondary" className="tabular-nums">
              {projectKey}
            </Badge>
            <ChevronRightIcon className="size-3.5 text-muted-foreground" aria-hidden="true" />
            <span className="font-medium">{m.new}</span>
          </div>
          <div className="flex flex-col gap-2 px-4 pt-3 pb-2">
            <input
              ref={titleRef}
              value={title}
              placeholder={m.titlePlaceholder}
              aria-label={m.titleLabel}
              aria-invalid={titleError ? true : undefined}
              aria-describedby={titleError ? "new-issue-title-error" : undefined}
              maxLength={200}
              autoComplete="off"
              onChange={(event) => {
                setTitle(event.target.value);
                setTitleError(false);
              }}
              onKeyDown={(event) => {
                // Plain Enter must not create a half-written issue.
                if (event.key === "Enter" && !event.ctrlKey && !event.metaKey) event.preventDefault();
              }}
              className="w-full bg-transparent text-lg leading-7 font-semibold outline-none placeholder:font-medium placeholder:text-muted-foreground"
            />
            {titleError ? (
              <p id="new-issue-title-error" role="alert" className="text-[0.8125rem] leading-5 text-destructive">
                {m.titleRequired}
              </p>
            ) : null}
            <RichTextEditor
              key={body.revision}
              initialContent={body.initial}
              placeholder={m.descriptionPlaceholder}
              ariaLabel={m.descriptionLabel}
              className="max-h-[45dvh] min-h-28 overflow-y-auto overscroll-contain"
              onChange={(doc) => {
                content.current = doc;
                bodyTouched.current = true;
              }}
            />
          </div>
          {mutation.isError ? (
            <div className="px-4 pb-2">
              <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert>
            </div>
          ) : null}
          <div className="flex flex-wrap items-center gap-1.5 px-4 pb-3">
            <ChipSelect label={m.status} value={properties.status} options={statusOptions} onChange={(value) => set("status", value)} />
            <ChipSelect
              label={m.priority}
              value={properties.priority}
              options={priorityOptions}
              onChange={(value) => set("priority", value)}
            />
            <ChipSelect
              label={m.assignee}
              value={properties.assigneeId ?? NONE}
              options={assigneeOptions(members)}
              onChange={(value) => set("assigneeId", value === NONE ? null : value)}
            />
            <ChipSelect
              label={m.preset}
              value={properties.preset ?? NONE}
              options={presetOptions}
              onChange={(value) => setPreset(value === NONE ? null : value)}
            />
            <DueDateChip value={properties.dueDate} onChange={(value) => set("dueDate", value)} />
          </div>
          <div className="flex flex-wrap items-center justify-end gap-3 border-t px-4 py-2.5">
            {lastCreated ? (
              <p role="status" className="mr-auto text-[0.8125rem] leading-5 text-muted-foreground">
                {m.created(lastCreated)}
              </p>
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
              {mutation.isPending ? null : <Kbd className="max-md:hidden">Ctrl ↵</Kbd>}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
