"use client";

import { useState } from "react";
import { CheckIcon, Trash2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ChipSelect } from "@/features/issues/components/chip-select";
import { PriorityMark } from "@/features/issues/components/issue-row";
import { STATUSES, StatusIcon, statusLabel } from "@/features/issues/meta";
import { messages } from "@/messages";
import {
  EXAMPLE_KEY_PREFIX,
  type ExampleIssue,
  type PracticeTask,
} from "../steps";

const m = messages.tour.practice;
const TITLE_MAX_LENGTH = 200;

const statusOptions = STATUSES.map((status) => ({
  value: status,
  label: statusLabel(status),
  icon: <StatusIcon status={status} />,
}));

/** The example issues and what has been tried on them. They live in the tour and nowhere else. */
export type Practice = {
  issues: ExampleIssue[];
  nextNumber: number;
  tried: PracticeTask[];
};

/**
 * A small issue list to try things on during the tour: add one, change a status, delete one.
 * It uses the controls of the real list, but everything stays in the browser and is gone when the
 * tour ends, so a new person can press anything without leaving something behind.
 */
export function TourPractice({
  task,
  practice,
  onChange,
}: {
  task: PracticeTask;
  practice: Practice;
  onChange: (update: (practice: Practice) => Practice) => void;
}) {
  const [title, setTitle] = useState("");
  const change = (
    done: PracticeTask,
    issues: (issues: ExampleIssue[]) => ExampleIssue[],
  ) =>
    onChange((current) => ({
      ...current,
      issues: issues(current.issues),
      tried: current.tried.includes(done)
        ? current.tried
        : [...current.tried, done],
    }));

  const add = () => {
    const trimmed = title.trim();
    if (!trimmed) return;
    onChange((current) => ({
      issues: [
        ...current.issues,
        {
          key: `${EXAMPLE_KEY_PREFIX}-${current.nextNumber}`,
          title: trimmed,
          status: "BACKLOG",
          priority: "NONE",
        },
      ],
      nextNumber: current.nextNumber + 1,
      tried: current.tried.includes("create")
        ? current.tried
        : [...current.tried, "create"],
    }));
    setTitle("");
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
        <span>{m.notice}</span>
        {practice.tried.includes(task) ? (
          <span
            role="status"
            className="flex shrink-0 items-center gap-1 font-medium text-foreground"
          >
            <CheckIcon className="size-3.5 text-success" aria-hidden="true" />
            {m.tried}
          </span>
        ) : null}
      </div>
      <div className="flex flex-col rounded-lg border">
        {practice.issues.length === 0 ? (
          <p className="px-3 py-4 text-center text-[0.8125rem] leading-5 text-muted-foreground">
            {m.empty}
          </p>
        ) : (
          <ul
            aria-label={m.listLabel}
            className="flex max-h-52 flex-col overflow-y-auto overscroll-contain py-1"
          >
            {practice.issues.map((issue) => (
              <li
                key={issue.key}
                className="flex h-9 items-center gap-2 px-2 text-[0.8125rem] leading-5 max-md:h-11"
              >
                <ChipSelect
                  iconOnly
                  label={messages.issues.statusOf(issue.key)}
                  value={issue.status}
                  options={statusOptions}
                  onChange={(status) =>
                    change("status", (issues) =>
                      issues.map((item) =>
                        item.key === issue.key ? { ...item, status } : item,
                      ),
                    )
                  }
                />
                <PriorityMark priority={issue.priority} />
                <span className="shrink-0 text-muted-foreground tabular-nums">
                  {issue.key}
                </span>
                <span className="min-w-0 flex-1 truncate font-medium">
                  {issue.title}
                </span>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="text-muted-foreground max-md:size-9"
                  aria-label={m.deleteOf(issue.key)}
                  onClick={() =>
                    change("delete", (issues) =>
                      issues.filter((item) => item.key !== issue.key),
                    )
                  }
                >
                  <Trash2Icon />
                </Button>
              </li>
            ))}
          </ul>
        )}
        <form
          className="flex items-center gap-2 border-t p-2"
          onSubmit={(event) => {
            event.preventDefault();
            add();
          }}
        >
          <Input
            value={title}
            maxLength={TITLE_MAX_LENGTH}
            placeholder={m.titlePlaceholder}
            aria-label={m.titleLabel}
            className="max-md:h-11"
            onChange={(event) => setTitle(event.target.value)}
          />
          <Button
            type="submit"
            variant="outline"
            className="max-md:h-11"
            disabled={!title.trim()}
          >
            {m.add}
          </Button>
        </form>
      </div>
    </div>
  );
}
