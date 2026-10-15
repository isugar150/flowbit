"use client";

import { useState } from "react";
import { UserIcon } from "lucide-react";
import { MultiCombobox } from "@/components/multi-combobox";
import { PeopleAvatars, PersonAvatar, personName, type PersonLike } from "@/components/person";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import type { ProjectMember } from "@/features/projects/types";
import { messages } from "@/messages";
import { MAX_ASSIGNEES, assigneeSummary } from "../options";
import { plainChipClass } from "./chip-select";

const m = messages.issues;

/**
 * The assignees of an issue as a quiet row of the side panel (D-125). It opens a list of the
 * people who may be given the issue, where any number of them (up to ten) are ticked.
 */
export function AssigneeChip({
  members,
  current = [],
  value,
  onChange,
  disabled,
}: {
  /** Who the issue can be given to. */
  members: ProjectMember[];
  /** The saved assignees, so that someone who has since left the project still shows and can be taken off. */
  current?: (PersonLike & { id: string })[];
  /** Ids of the assignees, in the order they were given the issue. */
  value: string[];
  onChange: (assigneeIds: string[]) => void;
  disabled?: boolean;
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
        render={<button type="button" disabled={disabled} aria-label={`${m.assignee}: ${summary}`} className={plainChipClass} />}
      >
        {selected.length === 0 ? (
          <UserIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        ) : selected.length === 1 ? (
          <PersonAvatar person={selected[0]} />
        ) : (
          <PeopleAvatars people={selected} />
        )}
        <span className="truncate">{summary}</span>
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
