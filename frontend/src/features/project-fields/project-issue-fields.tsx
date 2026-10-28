"use client";

import { useId, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { PencilIcon, PlusIcon, TrashIcon } from "lucide-react";
import { ListRowsSkeleton } from "@/components/skeletons";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { FormAlert } from "@/features/auth/components/form-alert";
import { PropertyValue } from "@/features/custom-properties/components/custom-properties";
import { CUSTOM_PROPERTY_TYPES, type CustomProperty } from "@/features/custom-properties/types";
import { useProjectApiScope } from "@/features/projects/project-api-scope";
import type { ProjectMember } from "@/features/projects/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { deleteProjectField, getProjectFields, projectFieldsKey, saveProjectField } from "./api";
import type { ProjectFieldInput, ProjectIssueField } from "./types";

const m = messages.projectFields;

/** A field with a value as the property editors take it: who a person value names comes from the members, or from the field when they left. */
export function fieldProperty(field: Pick<ProjectIssueField, "id" | "label" | "type" | "multiple" | "people">, value: CustomProperty["value"], members: ProjectMember[]): CustomProperty {
  const ids = Array.isArray(value) ? value : typeof value === "string" ? [value] : [];
  const people = field.type === "PERSON" ? ids.flatMap((id) => {
    const member = members.find((other) => other.userId === id);
    const person = member ? { id: member.userId, loginId: member.loginId, displayName: member.displayName, avatarUrl: member.avatarUrl } : field.people.find((other) => other.id === id);
    return person ? [person] : [];
  }) : [];
  return { id: field.id, label: field.label, type: field.type, value, person: people[0] ?? null, multiple: field.type === "PERSON" && field.multiple, people };
}

/** The default as the other way of choosing takes it: a list of ids becomes its first, one id a list of one. */
function defaultForChoice(value: ProjectFieldInput["defaultValue"], multiple: boolean): ProjectFieldInput["defaultValue"] {
  if (multiple) return typeof value === "string" ? [value] : value;
  return Array.isArray(value) ? (value[0] ?? null) : value;
}

function FieldEditor({ field, members, onSave, onCancel, pending, error }: { field?: ProjectIssueField; members: ProjectMember[]; onSave: (input: ProjectFieldInput) => void; onCancel: () => void; pending: boolean; error: unknown }) {
  const id = useId();
  const [input, setInput] = useState<ProjectFieldInput>(() => field ?? { label: "", type: "PERSON", defaultValue: null, multiple: true });
  return <form className="flex flex-col gap-4 rounded-lg border p-4" onSubmit={(event) => { event.preventDefault(); onSave(input); }}>
    <Field><FieldLabel htmlFor={`${id}-label`}>{m.name}</FieldLabel><Input id={`${id}-label`} required maxLength={30} value={input.label} placeholder={m.namePlaceholder} onChange={(event) => setInput({ ...input, label: event.target.value })} /></Field>
    <Field><FieldLabel htmlFor={`${id}-type`}>{m.type}</FieldLabel><NativeSelect id={`${id}-type`} value={input.type} disabled={!!field} onChange={(event) => setInput({ ...input, type: event.target.value as ProjectFieldInput["type"], defaultValue: null })}>{CUSTOM_PROPERTY_TYPES.map((type) => <NativeSelectOption key={type} value={type}>{messages.customProperties.types[type]}</NativeSelectOption>)}</NativeSelect><FieldDescription>{m.typeHint}</FieldDescription></Field>
    {input.type === "PERSON" ? <Field orientation="horizontal"><input id={`${id}-multiple`} type="checkbox" className="mt-0.5 size-4 cursor-pointer accent-primary" checked={input.multiple} onChange={(event) => setInput((current) => ({ ...current, multiple: event.target.checked, defaultValue: defaultForChoice(current.defaultValue, event.target.checked) }))} /><div className="flex flex-col gap-1"><FieldLabel htmlFor={`${id}-multiple`}>{m.multiple}</FieldLabel><FieldDescription>{m.multipleHint}</FieldDescription></div></Field> : null}
    <Field><FieldLabel>{m.defaultValue}</FieldLabel><PropertyValue key={`${input.type}-${input.multiple}`} item={fieldProperty({ id: field?.id ?? id, label: input.label || m.defaultValue, type: input.type, multiple: input.multiple, people: field?.people ?? [] }, input.defaultValue, members)} canEdit plain={false} members={members} onChange={(changes) => setInput((current) => ({ ...current, defaultValue: changes.value === undefined ? current.defaultValue : changes.value }))} /><FieldDescription>{m.defaultHint}</FieldDescription></Field>
    {error ? <FormAlert>{apiErrorMessage(error)}</FormAlert> : null}
    <div className="flex gap-2"><Button type="submit" disabled={pending}>{m.save}</Button><Button type="button" variant="ghost" onClick={onCancel}>{m.cancel}</Button></div>
  </form>;
}

export function ProjectIssueFields({ projectId, members }: { projectId: string; members: ProjectMember[] }) {
  const scope = useProjectApiScope();
  const client = useQueryClient();
  const key = projectFieldsKey(projectId, scope);
  const fields = useQuery({ queryKey: key, queryFn: () => getProjectFields(projectId, scope) });
  const [editing, setEditing] = useState<ProjectIssueField | "new" | null>(null);
  const invalidate = () => { void client.invalidateQueries({ queryKey: ["project-issue-fields", projectId] }); void client.invalidateQueries({ queryKey: ["custom-properties", "issue"] }); };
  const save = useRefreshingMutation((input: ProjectFieldInput) => saveProjectField(projectId, scope, editing && editing !== "new" ? editing.id : undefined, input), () => { invalidate(); setEditing(null); });
  const remove = useRefreshingMutation((id: string) => deleteProjectField(projectId, scope, id), invalidate);
  return <div className="flex flex-col gap-4">
    {fields.isPending ? <ListRowsSkeleton rows={2} label={m.loading} /> : null}
    {fields.error || remove.error ? <FormAlert>{apiErrorMessage(fields.error ?? remove.error)}</FormAlert> : null}
    <ul className="flex flex-col gap-2">{fields.data?.map((field) => <li key={field.id} className="flex items-center gap-2 rounded-lg border p-3">
      <div className="mr-auto min-w-0"><p className="truncate text-sm font-medium">{field.label}</p><p className="text-xs text-muted-foreground">{messages.customProperties.types[field.type]}{field.type === "PERSON" ? ` · ${field.multiple ? m.multipleBadge : m.singleBadge}` : ""}</p></div>
      <Button variant="ghost" size="icon-sm" aria-label={m.edit(field.label)} onClick={() => setEditing(field)}><PencilIcon /></Button>
      <Button variant="ghost" size="icon-sm" aria-label={m.remove(field.label)} disabled={remove.isPending} onClick={() => { if (window.confirm(m.confirmDelete(field.label))) remove.mutate(field.id); }}><TrashIcon /></Button>
    </li>)}</ul>
    {fields.data?.length === 0 ? <p className="text-sm text-muted-foreground">{m.empty}</p> : null}
    {editing ? <FieldEditor key={editing === "new" ? "new" : editing.id} field={editing === "new" ? undefined : editing} members={members} pending={save.isPending} error={save.error} onSave={(input) => save.mutate(input)} onCancel={() => setEditing(null)} /> : <div><Button variant="outline" size="sm" onClick={() => setEditing("new")} disabled={(fields.data?.length ?? 0) >= 20}><PlusIcon />{m.add}</Button></div>}
  </div>;
}
