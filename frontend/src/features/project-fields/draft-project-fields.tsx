"use client";

import { PropertyPanelRow } from "@/components/property-card";
import { PropertyTypeIcon, PropertyValue } from "@/features/custom-properties/components/custom-properties";
import type { CustomPropertyValue } from "@/features/custom-properties/types";
import type { ProjectMember } from "@/features/projects/types";
import { fieldProperty } from "./project-issue-fields";
import type { ProjectIssueField } from "./types";

export function DraftProjectFields({ fields, values, members, onChange }: { fields: ProjectIssueField[]; values: Record<string, CustomPropertyValue>; members: ProjectMember[]; onChange: (fieldId: string, value: CustomPropertyValue) => void }) {
  return fields.length ? <dl className="flex flex-col">{fields.map((field) => <PropertyPanelRow key={field.id} label={field.label} icon={<PropertyTypeIcon type={field.type} />}><PropertyValue item={fieldProperty(field, Object.hasOwn(values, field.id) ? values[field.id] : field.defaultValue, members)} canEdit plain members={members} onChange={(changes) => { if (changes.value !== undefined) onChange(field.id, changes.value); }} /></PropertyPanelRow>)}</dl> : null;
}
