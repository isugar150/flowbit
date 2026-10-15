"use client";

import { useState } from "react";
import { CircleUserRoundIcon, UserIcon } from "lucide-react";
import { MultiCombobox } from "@/components/multi-combobox";
import { PeopleAvatars, PersonAvatar, personName, type PersonLike } from "@/components/person";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import type { ProjectMember } from "@/features/projects/types";
import { messages } from "@/messages";
import { MAX_ASSIGNEES, assigneeSummary } from "../options";
import { cellChipClass, plainChipClass } from "./chip-select";

const m = messages.issues;

/**
 * The assignees of an issue as a quiet row of the side panel (D-125), or with `cell` as the
 * assignee column of a list line, drawn as the list draws it (IssueAssignee): avatars only. It opens a list of
 * the people who may be given the issue, where any number of them (up to ten) are ticked.
 */
export function AssigneeChip({
  members,
  current = [],
  value,
  onChange,
  disabled,
  cell,
  label = m.assignee,
}: {
  /** Who the issue can be given to. */
  members: ProjectMember[];
  /** The saved assignees, so that someone who has since left the project still shows and can be taken off. */
  current?: (PersonLike & { id: string })[];
  /** Ids of the assignees, in the order they were given the issue. */
  value: string[];
  onChange: (assigneeIds: string[]) => void;
  disabled?: boolean;
  cell?: boolean;
  /** What the button changes, for screen readers. */
  label?: string;
}) {
  const [tooMany, setTooMany] = useState(false);
  const people = [
    ...members.map((member) => ({ ...member, id: member.userId })),
    ...current.filter((person) => !members.some((member) => member.userId === person.id)),
  ];
  // In the issue's order.
  const selected = value.flatMap((id) => people.filter((person) => person.id === id));
  const summary = assigneeSummary(selected);

  return (
    <Popover onOpenChange={(open) => (open ? null : setTooMany(false))}>
      <PopoverTrigger
        render={
          <button
            type="button"
            disabled={disabled}
            aria-label={`${label}: ${summary}`}
            title={cell ? selected.map(personName).join(", ") || summary : undefined}
            className={cell ? cellChipClass : plainChipClass}
          />
        }
      >
        {cell ? (
          <>
            {selected.length === 0 ? (
              <CircleUserRoundIcon className="size-4.5 shrink-0 text-muted-foreground/60" aria-hidden="true" />
            ) : (
              <PeopleAvatars people={selected} max={2} />
            )}
          </>
        ) : selected.length === 0 ? (
          <UserIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        ) : selected.length === 1 ? (
          <PersonAvatar person={selected[0]} />
        ) : (
          <PeopleAvatars people={selected} />
        )}
        {/* A list cell shows faces only; the names are in the tooltip and the button's label. */}
        {cell ? null : <span className="truncate">{summary}</span>}
      </PopoverTrigger>
      <PopoverContent className="w-64 p-0">
        <MultiCombobox
          options={people.map((person) => ({
            id: person.id,
            label: personName(person),
            hint: person.displayName ? person.loginId : undefined,
            icon: <PersonAvatar person={person} />,
          }))}
          value={value}
          onChange={(next) => {
            const over = next.length > MAX_ASSIGNEES;
            setTooMany(over);
            if (!over) onChange(next);
          }}
          placeholder={m.assigneeSearchPlaceholder}
          listLabel={m.assigneeCandidates}
          emptyText={m.noAssigneeCandidates}
          error={tooMany ? m.tooManyAssignees(MAX_ASSIGNEES) : null}
        />
      </PopoverContent>
    </Popover>
  );
}
