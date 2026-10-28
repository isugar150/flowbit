import type { CustomPropertyType, CustomPropertyValue } from "@/features/custom-properties/types";
import type { IssuePerson } from "@/features/issues/types";
export type ProjectIssueField = { id: string; projectId: string; label: string; type: CustomPropertyType; defaultValue: CustomPropertyValue; person: IssuePerson | null };
export type ProjectFieldInput = Pick<ProjectIssueField, "label" | "type" | "defaultValue">;
export type IssueFieldInput = { fieldId: string; value: CustomPropertyValue };
