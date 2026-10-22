import { apiFetch } from "@/lib/api/client";
import type { CustomProperty, CustomPropertyChanges, CustomPropertyList, CustomPropertyOwner, CustomPropertyType, CustomPropertyValue } from "./types";

const base = (owner: CustomPropertyOwner) => `/api/v1/custom-properties/${owner.type}/${encodeURIComponent(owner.id)}`;

export const customPropertiesKey = (owner: CustomPropertyOwner) => ["custom-properties", owner.type, owner.id] as const;

export function getCustomProperties(owner: CustomPropertyOwner): Promise<CustomPropertyList> {
  return apiFetch<CustomPropertyList>(base(owner));
}

/** Adds a property to the item; a checkbox starts unchecked, any other kind empty unless `value` says. */
export function addCustomProperty(input: {
  owner: CustomPropertyOwner;
  label: string;
  type: CustomPropertyType;
  value?: CustomPropertyValue;
}): Promise<CustomProperty> {
  return apiFetch<CustomProperty>(base(input.owner), { method: "POST", body: { label: input.label, type: input.type, value: input.value ?? null } });
}

export function changeCustomProperty(input: { id: string; changes: CustomPropertyChanges }): Promise<CustomProperty> {
  return apiFetch<CustomProperty>(`/api/v1/custom-properties/${input.id}`, { method: "PATCH", body: input.changes });
}

export function removeCustomProperty(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/custom-properties/${id}`, { method: "DELETE" });
}
