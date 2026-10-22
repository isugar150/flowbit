import type { IssuePerson } from "@/features/issues/types";

/** What a property holds (D-186). Fixed when it is added. */
export type CustomPropertyType = "TEXT" | "NUMBER" | "DATE" | "CHECKBOX" | "LINK" | "PERSON";

export const CUSTOM_PROPERTY_TYPES: CustomPropertyType[] = ["TEXT", "NUMBER", "DATE", "CHECKBOX", "LINK", "PERSON"];

/** The item a property belongs to: the word in the address and the id of its row (an issue's `id`, not its number). */
export type CustomPropertyOwner = { type: "project" | "milestone" | "issue"; id: string };

/** A string for TEXT, LINK, DATE (`YYYY-MM-DD`) and PERSON (the user's id), a number, a boolean; null while empty. */
export type CustomPropertyValue = string | number | boolean | null;

export type CustomProperty = {
  id: string;
  label: string;
  type: CustomPropertyType;
  value: CustomPropertyValue;
  /** Who a PERSON value is. */
  person: IssuePerson | null;
};

/** GET /api/v1/custom-properties/{type}/{id} */
export type CustomPropertyList = { canEdit: boolean; items: CustomProperty[] };

/** What may be changed on a property: its value, and its name. The kind stays. */
export type CustomPropertyChanges = { value?: CustomPropertyValue; label?: string };
