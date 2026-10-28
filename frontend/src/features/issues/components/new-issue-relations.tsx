"use client";

import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { messages } from "@/messages";
import { StatusIcon } from "../meta";
import { RelationPicker, type AddableKind, type RelationTarget } from "./issue-relations";

const m = messages.issues.relations;

/** How many relations a ticket can be made with (the same limit as the server's). */
const MAX_RELATIONS = 20;

/** A relation asked for while the ticket is being made: how the new ticket stands to another one (D-237). */
export type DraftRelation = { kind: AddableKind; target: RelationTarget };

const OPPOSITE: Partial<Record<AddableKind, AddableKind>> = { BLOCKS: "BLOCKED_BY", BLOCKED_BY: "BLOCKS" };

/**
 * The "관계" of the new ticket dialog: the tickets this one blocks, is blocked by, duplicates or relates to, picked the same way as on
 * a ticket's page but only kept here. They are sent with the ticket and made together with it.
 */
export function NewIssueRelations({ relations, onChange }: { relations: DraftRelation[]; onChange: (next: DraftRelation[]) => void }) {
  // A refusal is thrown for the picker to say, so that it stays open.
  const add = (kind: AddableKind, target: RelationTarget) => {
    if (relations.some((relation) => relation.kind === kind && relation.target.number === target.number)) throw new Error(m.alreadyChosen);
    // Blocking and being blocked by the same ticket could never be started.
    const opposite = OPPOSITE[kind];
    if (opposite && relations.some((relation) => relation.kind === opposite && relation.target.number === target.number)) throw new Error(m.conflictChosen);
    if (relations.length >= MAX_RELATIONS) throw new Error(m.tooManyChosen(MAX_RELATIONS));
    onChange([...relations, { kind, target }]);
  };

  return (
    <section aria-labelledby="new-issue-relations-title" className="mt-3 flex flex-col gap-1 border-t pt-3">
      <div className="flex h-8 items-center gap-2">
        <h3 id="new-issue-relations-title" className="text-[0.8125rem] leading-5 font-medium text-muted-foreground">
          {m.heading}
        </h3>
        <span className="ml-auto">
          <RelationPicker onPick={add} />
        </span>
      </div>
      {relations.length === 0 ? <p className="text-xs leading-4 text-muted-foreground">{m.newNone}</p> : null}
      <ul>
        {relations.map((relation) => (
          <li key={`${relation.kind}:${relation.target.number}`} className="flex items-center gap-2 py-1 text-[0.8125rem] leading-5">
            <StatusIcon status={relation.target.status} />
            <span className="min-w-0 flex-1">
              <span className="block text-xs leading-4 text-muted-foreground">{m.kinds[relation.kind]}</span>
              <span className="flex min-w-0 items-baseline gap-1.5">
                <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{relation.target.key}</span>
                <span className="truncate font-medium">{relation.target.title}</span>
              </span>
            </span>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={m.remove(relation.target.key)}
              onClick={() => onChange(relations.filter((other) => other !== relation))}
            >
              <XIcon />
            </Button>
          </li>
        ))}
      </ul>
    </section>
  );
}
