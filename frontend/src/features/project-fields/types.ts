import type { CustomPropertyType, CustomPropertyValue } from "@/features/custom-properties/types";
import type { IssuePerson } from "@/features/issues/types";
export type ProjectIssueField = {
  id: string;
  projectId: string;
  label: string;
  type: CustomPropertyType;
  /** A list of user ids for a PERSON field that takes several people, an id for one that takes one (D-238). */
  defaultValue: CustomPropertyValue;
  /** The first person the default names. */
  person: IssuePerson | null;
  /** For a PERSON field: several people (the default for a new one) or one. Always true for the other types. */
  multiple: boolean;
  /** Who the default names, in order. */
  people: IssuePerson[];
};
export type ProjectFieldInput = Pick<ProjectIssueField, "label" | "type" | "defaultValue" | "multiple">;
export type IssueFieldInput = { fieldId: string; value: CustomPropertyValue };
