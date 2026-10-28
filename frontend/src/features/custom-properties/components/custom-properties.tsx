"use client";

import { useState } from "react";
import {
  CalendarIcon,
  EllipsisIcon,
  ExternalLinkIcon,
  HashIcon,
  LinkIcon,
  PlusIcon,
  SquareCheckIcon,
  Trash2Icon,
  TypeIcon,
  UserIcon,
  type LucideIcon,
} from "lucide-react";
import { MultiCombobox } from "@/components/multi-combobox";
import { PeopleAvatars, PersonAvatar, personName } from "@/components/person";
import { PropertyPanelRow, PropertyRow } from "@/components/property-card";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { FormAlert } from "@/features/auth/components/form-alert";
import { ChipSelect, chipClass, plainChipClass, type ChipOption } from "@/features/issues/components/chip-select";
import { DateChip } from "@/features/issues/components/date-chips";
import { NONE } from "@/features/issues/options";
import type { IssuePerson } from "@/features/issues/types";
import type { ProjectMember } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRemoteValue } from "@/lib/use-remote-value";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { CUSTOM_PROPERTY_TYPES, type CustomProperty, type CustomPropertyChanges, type CustomPropertyOwner, type CustomPropertyType } from "../types";
import { useCustomProperties } from "../use-custom-properties";

const m = messages.customProperties;
const LABEL_MAX_LENGTH = 30;

/**
 * Where the properties sit, which says how a row and its value are drawn: the project's card (pills) or an issue's
 * side panel (quiet rows, in the panel's first group, right under the due date). A word, not a component, so
 * that a page rendered on the server can ask for it.
 */
export type CustomPropertiesLayout = "card" | "panel";

/** A text field that looks like the chips around it: a pill, or with `plain` a quiet row that shows its edge when pointed at. */
export const fieldClass = (plain: boolean) =>
  plain
    ? "-mx-2 h-8 w-full min-w-0 rounded-md bg-transparent px-2 text-[0.8125rem] leading-5 font-medium outline-none placeholder:font-normal placeholder:text-muted-foreground hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-10"
    : "h-7 w-full min-w-0 rounded-full border bg-background px-2.5 text-[0.8125rem] leading-5 outline-none placeholder:text-muted-foreground hover:bg-muted focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-9";

const isFocused = (input: HTMLInputElement) => document.activeElement === input;

const typeIcon: Record<CustomPropertyType, LucideIcon> = {
  TEXT: TypeIcon,
  NUMBER: HashIcon,
  DATE: CalendarIcon,
  CHECKBOX: SquareCheckIcon,
  LINK: LinkIcon,
  PERSON: UserIcon,
};

export function PropertyTypeIcon({ type }: { type: CustomPropertyType }) {
  const Icon = typeIcon[type];
  return <Icon className="size-4 shrink-0" aria-hidden="true" />;
}

/** The kinds of value to choose from, each with its mark. */
const TYPE_OPTIONS: ChipOption<CustomPropertyType>[] = CUSTOM_PROPERTY_TYPES.map((kind) => {
  const Icon = typeIcon[kind];
  return { value: kind, label: m.types[kind], icon: <Icon className="size-4 text-muted-foreground" aria-hidden="true" /> };
});

/**
 * The properties people added to one project or issue (D-186), as rows of the list the page has: the label and its value, changed in place, with a menu to delete it, and a "속성 추가" at
 * the end. Whoever may edit the item adds, changes and deletes; everyone else reads. Nothing shows
 * for someone who only reads when there is none. `members` are who a "사람" property can be set to.
 *
 * In an issue's panel (`layout="panel"`) they continue the first group, so "속성 추가" stands right under the
 * due date when there are none (D-189); in the project's card they continue the card's rows.
 */
export function CustomProperties({
  owner,
  members,
  watch,
  layout,
}: {
  owner: CustomPropertyOwner;
  members: ProjectMember[];
  /** What the server last rendered the page from; when it changes the properties are read again. */
  watch?: unknown;
  layout: CustomPropertiesLayout;
}) {
  const plain = layout !== "card";
  const Row = layout === "card" ? PropertyRow : PropertyPanelRow;
  const { query, add, change, remove, error } = useCustomProperties(owner, watch);
  const list = query.data;
  if (!list) {
    return query.isError ? (
      <p role="alert" className="px-1 text-xs leading-4 text-destructive">
        {m.loadFailed}
      </p>
    ) : null;
  }
  const canEdit = list.canEdit;
  if (list.items.length === 0 && !canEdit) return null;

  const rows = (
    <>
      {list.items.map((item) => (
        <Row key={item.id} label={item.label} icon={<PropertyTypeIcon type={item.type} />}>
          <div className="flex min-w-0 flex-1 items-center gap-1">
            <div className="min-w-0 flex-1">
              <PropertyValue
                item={item}
                canEdit={canEdit}
                plain={plain}
                members={members}
                onChange={(changes, person) => change.mutate({ id: item.id, changes, person })}
              />
            </div>
            {canEdit && !item.projectFieldId ? <RemoveMenu label={item.label} onRemove={() => remove.mutate(item.id)} /> : null}
          </div>
        </Row>
      ))}
    </>
  );
  const alert = error ? <FormAlert>{apiErrorMessage(error)}</FormAlert> : null;
  // The card already is a list of properties, and its page puts them in a <dl>.
  if (layout === "card") {
    return (
      <>
        {alert}
        {rows}
        {canEdit ? <AddSavedProperty add={add} /> : null}
      </>
    );
  }
  // The panel's other rows are not a list: the added ones, which have labels, are, and the way to add one follows them.
  return (
    <>
      {alert}
      {list.items.length > 0 ? <dl className="flex flex-col">{rows}</dl> : null}
      {canEdit ? <AddSavedProperty add={add} /> : null}
    </>
  );
}

/** The "…" at the end of a property's row, with the one thing it does: delete it. */
export function RemoveMenu({ label, onRemove }: { label: string; onRemove: () => void }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button variant="ghost" size="icon-xs" aria-label={m.menuOf(label)} className="shrink-0 text-muted-foreground max-md:size-8" />}
      >
        <EllipsisIcon aria-hidden="true" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-40">
        <DropdownMenuItem variant="destructive" onClick={onRemove}>
          <Trash2Icon />
          {m.remove}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** The editor for one value, by what kind it is. Without the right to edit it only shows it. */
export function PropertyValue({
  item,
  canEdit,
  plain,
  members,
  onChange,
}: {
  item: CustomProperty;
  canEdit: boolean;
  plain: boolean;
  members: ProjectMember[];
  onChange: (changes: CustomPropertyChanges, person?: IssuePerson | null) => void;
}) {
  switch (item.type) {
    case "DATE":
      return (
        <DateChip
          plain={plain}
          label={item.label}
          placeholder={m.emptyValue}
          value={typeof item.value === "string" ? item.value : null}
          disabled={!canEdit}
          onChange={(value) => onChange({ value })}
        />
      );
    case "CHECKBOX":
      return (
        <span className="flex h-8 items-center max-md:h-10">
          <input
            type="checkbox"
            aria-label={item.label}
            checked={item.value === true}
            disabled={!canEdit}
            onChange={(event) => onChange({ value: event.target.checked })}
            className="size-4 cursor-pointer accent-primary disabled:cursor-default"
          />
        </span>
      );
    case "PERSON":
      return item.multiple ? (
        <PeopleValue item={item} canEdit={canEdit} plain={plain} members={members} onChange={onChange} />
      ) : (
        <PersonValue item={item} canEdit={canEdit} plain={plain} members={members} onChange={onChange} />
      );
    default:
      return <TextValue item={item} canEdit={canEdit} plain={plain} onChange={onChange} />;
  }
}

/** A text, a number or a link: typed in place and saved when the field is left (Enter too, Escape gives the typing up). */
function TextValue({
  item,
  canEdit,
  plain,
  onChange,
}: {
  item: CustomProperty;
  canEdit: boolean;
  plain: boolean;
  onChange: (changes: CustomPropertyChanges) => void;
}) {
  const current = item.value === null || typeof item.value === "boolean" ? "" : String(item.value);
  const field = useRemoteValue<HTMLInputElement>(current, isFocused);
  const [problem, setProblem] = useState<string | null>(null);
  const link = item.type === "LINK" && current ? current : null;

  if (!canEdit) {
    if (!current) return <span className="flex h-8 items-center text-muted-foreground max-md:h-10">{m.emptyValue}</span>;
    return link ? (
      <a
        href={link}
        target="_blank"
        rel="noopener noreferrer"
        className="flex h-8 min-w-0 items-center gap-1 text-primary underline-offset-2 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-10"
      >
        <span className="truncate">{current}</span>
        <ExternalLinkIcon className="size-3.5 shrink-0" aria-hidden="true" />
      </a>
    ) : (
      <span className="flex h-8 min-w-0 items-center max-md:h-10" title={current}>
        <span className="truncate">{current}</span>
      </span>
    );
  }

  const commit = (input: HTMLInputElement) => {
    const raw = input.value.trim();
    if (raw === current) {
      input.value = current;
      setProblem(null);
      return;
    }
    if (item.type === "NUMBER" && raw !== "") {
      const number = Number(raw.replace(/,/g, ""));
      if (!Number.isFinite(number)) {
        setProblem(m.notANumber);
        input.value = current;
        return;
      }
      setProblem(null);
      onChange({ value: number });
      return;
    }
    setProblem(null);
    onChange({ value: raw === "" ? null : raw });
  };

  return (
    <div className="flex min-w-0 items-center gap-1">
      <input
        ref={field}
        defaultValue={current}
        type="text"
        inputMode={item.type === "NUMBER" ? "decimal" : item.type === "LINK" ? "url" : "text"}
        maxLength={500}
        autoComplete="off"
        placeholder={m.emptyValue}
        aria-label={m.valueOf(item.label)}
        aria-invalid={problem !== null}
        title={problem ?? undefined}
        onBlur={(event) => commit(event.currentTarget)}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) return;
          if (event.key === "Enter") event.currentTarget.blur();
          if (event.key === "Escape") {
            // Gives the typing up; whatever holds the field (a dialog) stays open.
            event.stopPropagation();
            event.currentTarget.value = current;
            event.currentTarget.blur();
          }
        }}
        className={cn(fieldClass(plain), problem !== null && "ring-3 ring-destructive/40")}
      />
      {link ? (
        <a
          href={link}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={m.openLink}
          title={m.openLink}
          className="flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:size-9"
        >
          <ExternalLinkIcon className="size-4" aria-hidden="true" />
        </a>
      ) : null}
    </div>
  );
}

/** One person, picked from the members of the project (or whoever has been named already, who may have left it). */
function PersonValue({
  item,
  canEdit,
  plain,
  members,
  onChange,
}: {
  item: CustomProperty;
  canEdit: boolean;
  plain: boolean;
  members: ProjectMember[];
  onChange: (changes: CustomPropertyChanges, person?: IssuePerson | null) => void;
}) {
  const known: IssuePerson[] = members.map(({ userId, loginId, displayName, avatarUrl }) => ({ id: userId, loginId, displayName, avatarUrl }));
  if (item.person && !known.some((person) => person.id === item.person?.id)) known.push(item.person);
  const options: ChipOption<string>[] = [
    { value: NONE, label: m.noPerson, muted: true, icon: <UserIcon className="size-4 text-muted-foreground" aria-hidden="true" /> },
    ...known.map((person) => ({ value: person.id, label: personName(person), icon: <PersonAvatar person={person} /> })),
  ];
  return (
    <ChipSelect
      plain={plain}
      label={item.label}
      value={typeof item.value === "string" ? item.value : NONE}
      options={options}
      disabled={!canEdit}
      onChange={(value) => onChange({ value: value === NONE ? null : value }, value === NONE ? null : (known.find((person) => person.id === value) ?? null))}
    />
  );
}

/**
 * Several people, ticked from the members of the project (or whoever has been named already, who may have left it). Only a
 * common field of the project can take several (D-238); each tick is saved at once, as a list of ids.
 */
function PeopleValue({
  item,
  canEdit,
  plain,
  members,
  onChange,
}: {
  item: CustomProperty;
  canEdit: boolean;
  plain: boolean;
  members: ProjectMember[];
  onChange: (changes: CustomPropertyChanges, person?: IssuePerson | null) => void;
}) {
  const known: IssuePerson[] = members.map(({ userId, loginId, displayName, avatarUrl }) => ({ id: userId, loginId, displayName, avatarUrl }));
  for (const person of item.people ?? []) if (!known.some((other) => other.id === person.id)) known.push(person);
  const ids = Array.isArray(item.value) ? item.value : typeof item.value === "string" ? [item.value] : [];
  // In the order they were ticked.
  const selected = ids.flatMap((id) => known.filter((person) => person.id === id));
  const summary =
    selected.length === 0 ? m.noPerson : selected.length === 1 ? personName(selected[0]) : m.peopleSummary(personName(selected[0]), selected.length - 1);

  return (
    <Popover>
      <PopoverTrigger
        render={<button type="button" disabled={!canEdit} aria-label={`${item.label}: ${summary}`} className={plain ? plainChipClass : chipClass} />}
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
          options={known.map((person) => ({
            id: person.id,
            label: personName(person),
            hint: person.displayName ? person.loginId : undefined,
            icon: <PersonAvatar person={person} />,
          }))}
          value={ids}
          onChange={(next) => onChange({ value: next.length > 0 ? next : null })}
          placeholder={m.searchPerson}
          listLabel={m.personCandidates}
          emptyText={m.noPeopleCandidates}
        />
      </PopoverContent>
    </Popover>
  );
}

/** "속성 추가" on an item that exists: the property is saved at once. */
function AddSavedProperty({ add }: { add: ReturnType<typeof useCustomProperties>["add"] }) {
  return (
    <AddPropertyPopover
      onAdd={(input, done) => add.mutate(input, { onSuccess: done })}
      pending={add.isPending}
      error={add.isError ? apiErrorMessage(add.error) : null}
      onClosed={add.reset}
    />
  );
}

/**
 * "속성 추가": a name and a kind of value, then the property is there, empty, to be filled in. What
 * adding does is the caller's (`onAdd`, which calls `done` once it worked: the form closes and empties);
 * an item that exists saves it, a ticket being made keeps it until the ticket is (D-193).
 */
export function AddPropertyPopover({
  onAdd,
  pending = false,
  error = null,
  onClosed,
}: {
  onAdd: (input: { label: string; type: CustomPropertyType }, done: () => void) => void;
  pending?: boolean;
  /** Why the last add did not work, said in the form. */
  error?: string | null;
  /** The form was closed: whatever it said about the last add is no longer true. */
  onClosed?: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [label, setLabel] = useState("");
  const [type, setType] = useState<CustomPropertyType>("TEXT");
  const name = label.trim();

  return (
    <div className="flex min-h-8 items-center px-1">
      <Popover
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (!next) onClosed?.();
        }}
      >
        <PopoverTrigger
          render={
            <button
              type="button"
              className="-mx-2 flex h-8 items-center gap-1.5 rounded-md px-2 text-[0.8125rem] leading-5 text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-10"
            />
          }
        >
          <PlusIcon className="size-4" aria-hidden="true" />
          {m.add}
        </PopoverTrigger>
        <PopoverContent className="w-64 p-3">
          <form
            className="flex flex-col gap-3"
            onSubmit={(event) => {
              event.preventDefault();
              // The form is drawn in a portal but React still hands the event up to the form around the button, which in a new ticket makes the ticket.
              event.stopPropagation();
              if (!name || pending) return;
              onAdd({ label: name, type }, () => {
                setOpen(false);
                setLabel("");
                setType("TEXT");
              });
            }}
          >
            <p className="text-xs leading-4 text-muted-foreground">{m.addHint}</p>
            <label className="flex flex-col gap-1 text-xs leading-4 text-muted-foreground">
              {m.nameLabel}
              <Input
                autoFocus
                value={label}
                maxLength={LABEL_MAX_LENGTH}
                placeholder={m.namePlaceholder}
                autoComplete="off"
                onChange={(event) => setLabel(event.target.value)}
                className="text-foreground"
              />
            </label>
            <div className="flex flex-col gap-1 text-xs leading-4 text-muted-foreground">
              {m.typeLabel}
              {/* The app's own menu (as for a status or a priority), not the browser's list. */}
              <ChipSelect label={m.typeLabel} value={type} options={TYPE_OPTIONS} onChange={setType} />
            </div>
            {error ? <FormAlert>{error}</FormAlert> : null}
            <Button type="submit" size="sm" disabled={!name || pending}>
              {m.create}
            </Button>
          </form>
        </PopoverContent>
      </Popover>
    </div>
  );
}
