"use client";

import { useState } from "react";
import { PropertyPanelRow } from "@/components/property-card";
import type { ProjectMember } from "@/features/projects/types";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { MAX_DRAFTS, draftWithBadNumber, isTyped, newDraft, type DraftProperty } from "../drafts";
import type { CustomProperty } from "../types";
import { AddPropertyPopover, PropertyValue, RemoveMenu, fieldClass } from "./custom-properties";

const m = messages.customProperties;

/**
 * The properties of a ticket being made, in the same rows as those of one that exists, with "속성 추가" at the
 * end. Nothing is sent from here: the new-ticket dialog holds the list and sends it with the ticket.
 * `members` are who a "사람" property can be set to.
 */
export function DraftProperties({
  drafts,
  onChange,
  members,
  reservedLabels = [],
}: {
  drafts: DraftProperty[];
  onChange: (drafts: DraftProperty[]) => void;
  members: ProjectMember[];
  reservedLabels?: string[];
}) {
  const [problem, setProblem] = useState<string | null>(null);
  const set = (key: string, value: DraftProperty["value"]) => onChange(drafts.map((draft) => (draft.key === key ? { ...draft, value } : draft)));
  const bad = draftWithBadNumber(drafts);

  return (
    <>
      {drafts.length > 0 ? (
        <dl className="flex flex-col">
          {drafts.map((draft) => (
            <PropertyPanelRow key={draft.key} label={draft.label}>
              <div className="flex min-w-0 flex-1 items-center gap-1">
                <div className="min-w-0 flex-1">
                  {isTyped(draft.type) ? (
                    <input
                      type="text"
                      value={typeof draft.value === "string" ? draft.value : ""}
                      inputMode={draft.type === "NUMBER" ? "decimal" : draft.type === "LINK" ? "url" : "text"}
                      maxLength={500}
                      autoComplete="off"
                      placeholder={m.emptyValue}
                      aria-label={m.valueOf(draft.label)}
                      aria-invalid={bad?.key === draft.key}
                      title={bad?.key === draft.key ? m.notANumber : undefined}
                      onChange={(event) => set(draft.key, event.target.value)}
                      onKeyDown={(event) => {
                        // Plain Enter must not create a half-written ticket (Ctrl/⌘+Enter does, from anywhere in the form).
                        if (event.key === "Enter" && !event.ctrlKey && !event.metaKey) event.preventDefault();
                      }}
                      className={cn(fieldClass(true), bad?.key === draft.key && "ring-3 ring-destructive/40")}
                    />
                  ) : (
                    <PropertyValue
                      item={asProperty(draft)}
                      canEdit
                      plain
                      members={members}
                      onChange={(changes) => {
                        // A date, a checkbox and a person are never a number.
                        if (changes.value !== undefined && typeof changes.value !== "number") set(draft.key, changes.value);
                      }}
                    />
                  )}
                </div>
                <RemoveMenu label={draft.label} onRemove={() => onChange(drafts.filter((other) => other.key !== draft.key))} />
              </div>
            </PropertyPanelRow>
          ))}
        </dl>
      ) : null}
      <AddPropertyPopover
        error={problem}
        onClosed={() => setProblem(null)}
        onAdd={({ label, type }, done) => {
          if (drafts.length >= MAX_DRAFTS) return setProblem(m.tooMany(MAX_DRAFTS));
          // The same as on a saved ticket: a name is taken whatever its case.
          if (drafts.some((draft) => draft.label.toLowerCase() === label.toLowerCase()) || reservedLabels.some((reserved) => reserved.toLowerCase() === label.toLowerCase())) return setProblem(m.exists);
          onChange([...drafts, newDraft(label, type)]);
          setProblem(null);
          done();
        }}
      />
    </>
  );
}

/** A draft as the editors of saved properties take it. */
function asProperty(draft: DraftProperty): CustomProperty {
  return { id: draft.key, label: draft.label, type: draft.type, value: draft.value, person: null };
}
