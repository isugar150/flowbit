import type { CustomPropertyType, CustomPropertyValue, NewCustomPropertyInput } from "./types";

/**
 * A property of a ticket that is being made (D-193). It is not saved yet, so there is no id: `key` tells the rows apart.
 * A text, a number and a link are kept as typed (a string, "" for none) and turned into their value when the ticket is
 * made; a date and a person are a string or null, a checkbox a boolean.
 */
export type DraftProperty = { key: string; label: string; type: CustomPropertyType; value: string | boolean | null };

/** The most a ticket can have (the same as one that exists). */
export const MAX_DRAFTS = 20;

let draftCount = 0;

export const isTyped = (type: CustomPropertyType) => type === "TEXT" || type === "NUMBER" || type === "LINK";

/** A property with no value yet; a checkbox starts unchecked. */
export function newDraft(label: string, type: CustomPropertyType): DraftProperty {
  draftCount += 1;
  const value = isTyped(type) ? "" : type === "CHECKBOX" ? false : null;
  return { key: `draft-${draftCount}`, label, type, value };
}

/** The number a typed text means (commas are dropped); NaN when it is not one. A blank is no value, so it is not asked about. */
const numberOf = (text: string) => Number(text.trim().replace(/,/g, ""));

/** A number property whose text is not a number, the first one; the ticket cannot be made until it is fixed. */
export function draftWithBadNumber(drafts: DraftProperty[]): DraftProperty | undefined {
  return drafts.find(
    (draft) => draft.type === "NUMBER" && typeof draft.value === "string" && draft.value.trim() !== "" && !Number.isFinite(numberOf(draft.value)),
  );
}

function valueOf(draft: DraftProperty): CustomPropertyValue {
  if (!isTyped(draft.type)) return draft.value;
  const text = typeof draft.value === "string" ? draft.value.trim() : "";
  if (text === "") return null;
  return draft.type === "NUMBER" ? numberOf(text) : text;
}

/** What to send with the new ticket, in the order they are shown. */
export function draftsToInput(drafts: DraftProperty[]): NewCustomPropertyInput[] {
  return drafts.map((draft) => ({ label: draft.label, type: draft.type, value: valueOf(draft) }));
}

/** A person is one of the project's members, so changing the project empties them (the same as the assignees). */
export function withoutPeople(drafts: DraftProperty[]): DraftProperty[] {
  return drafts.map((draft) => (draft.type === "PERSON" ? { ...draft, value: null } : draft));
}
