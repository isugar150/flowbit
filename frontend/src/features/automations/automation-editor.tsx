"use client";

import { useId, useState } from "react";
import { DndContext, DragOverlay, MouseSensor, TouchSensor, closestCenter, useDraggable, useDroppable, useSensor, useSensors, type DragEndEvent } from "@dnd-kit/core";
import { ArrowDownIcon, ArrowUpIcon, BellIcon, CircleHelpIcon, GripVerticalIcon, PlusIcon, TagIcon, TrashIcon, UserRoundIcon, WorkflowIcon } from "lucide-react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { CheckSetting } from "@/components/setting-fields";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { MultiCombobox, type ComboboxOption } from "@/components/multi-combobox";
import type { ProjectMember } from "@/features/projects/types";
import type { ProjectIssueField } from "@/features/project-fields/types";
import type { Label } from "@/features/issues/types";
import { useIssueStatuses } from "@/features/issues/statuses";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { cn } from "@/lib/utils";
import { randomId } from "@/lib/random-id";
import { messages } from "@/messages";
import { moveBlock } from "./pipeline";
import { MESSAGE_VARIABLES, previewMessage, suggestedMessage } from "./message-template";
import type { ActionType, Audience, AutomationAction, AutomationInput } from "./types";

const m = messages.automations;
const actionTypes: ActionType[] = ["NOTIFY", "ASSIGN", "ADD_LABELS", "REMOVE_LABELS"];
const icons = { NOTIFY: BellIcon, ASSIGN: UserRoundIcon, ADD_LABELS: TagIcon, REMOVE_LABELS: TagIcon };
const newAction = (type: ActionType): AutomationAction => ({ id: randomId(), type, audience: "PEOPLE", userIds: [], labelIds: [], message: "" });

function MultiPick({ label, options, value, onChange }: { label: string; options: ComboboxOption[]; value: string[]; onChange: (ids: string[]) => void }) {
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <Popover>
        <PopoverTrigger render={<Button type="button" variant="outline" className="h-auto min-h-9 justify-start whitespace-normal" />}>
          {value.length ? value.map((id) => options.find((option) => option.id === id)?.label ?? m.noOptions).join(", ") : label}
        </PopoverTrigger>
        <PopoverContent align="start" className="w-72 p-2">
          <MultiCombobox options={options} value={value} onChange={onChange} placeholder={m.search} listLabel={label} emptyText={m.noOptions} />
        </PopoverContent>
      </Popover>
    </Field>
  );
}

function PaletteBlock({ type, onAdd, disabled }: { type: ActionType; onAdd: () => void; disabled: boolean }) {
  const { setNodeRef, listeners, attributes, isDragging } = useDraggable({ id: `palette:${type}`, data: { type, source: "palette" }, disabled });
  const Icon = icons[type];
  return (
    <div ref={setNodeRef} className={cn("flex items-center gap-1 rounded-lg border bg-background p-1", isDragging && "opacity-50")}>
      <Button type="button" variant="ghost" size="icon-sm" aria-label={`${m.drag}: ${m.actions[type]}`} {...listeners} {...attributes} style={{ touchAction: "none" }} disabled={disabled}><GripVerticalIcon /></Button>
      <Button type="button" variant="ghost" size="sm" onClick={onAdd} disabled={disabled}><Icon />{m.actions[type]}<PlusIcon /></Button>
    </div>
  );
}

function Gap({ index, empty }: { index: number; empty: boolean }) {
  const { setNodeRef, isOver } = useDroppable({ id: `gap:${index}`, data: { index } });
  return <div ref={setNodeRef} aria-label={m.drop} className={cn("flex items-center justify-center rounded-lg border border-dashed text-xs text-muted-foreground", empty ? "min-h-20 p-4" : "h-7 border-transparent", isOver && "border-primary bg-accent")}>{empty || isOver ? m.drop : <ArrowDownIcon className="size-3" aria-hidden="true" />}</div>;
}

function ActionBlock({ action, index, last, onChange, onRemove, onMove, members, labels, fields, template, preview }: {
  template: string; preview: string;
  action: AutomationAction; index: number; last: boolean; onChange: (action: AutomationAction) => void;
  onRemove: () => void; onMove: (direction: number) => void; members: ProjectMember[]; labels: Label[]; fields: ProjectIssueField[];
}) {
  const { setNodeRef, listeners, attributes, isDragging } = useDraggable({ id: action.id, data: { source: "action" } });
  const id = useId();
  const Icon = icons[action.type];
  const eligible = action.type === "ASSIGN" ? members.filter((member) => member.role !== "VIEWER") : members;
  return (
    <div ref={setNodeRef} className={cn("flex flex-col gap-4 rounded-xl border bg-background p-4", isDragging && "opacity-40")}>
      <div className="flex flex-wrap items-center gap-1">
        <Button type="button" variant="ghost" size="icon-sm" aria-label={`${m.drag}: ${m.actions[action.type]}`} {...listeners} {...attributes} style={{ touchAction: "none" }}><GripVerticalIcon /></Button>
        <Icon aria-hidden="true" className="size-4" /><h4 className="mr-auto text-sm font-medium">{index + 1}. {m.actions[action.type]}</h4>
        <Button type="button" variant="ghost" size="icon-sm" aria-label={m.up} disabled={index === 0} onClick={() => onMove(-1)}><ArrowUpIcon /></Button>
        <Button type="button" variant="ghost" size="icon-sm" aria-label={m.down} disabled={last} onClick={() => onMove(1)}><ArrowDownIcon /></Button>
        <Button type="button" variant="ghost" size="icon-sm" aria-label={m.remove} onClick={onRemove}><TrashIcon /></Button>
      </div>
      {action.type === "NOTIFY" ? <Field>
        <FieldLabel htmlFor={`${id}-audience`}>{m.audience}</FieldLabel>
        <NativeSelect id={`${id}-audience`} value={action.audience} onChange={(event) => onChange({ ...action, audience: event.target.value as Audience, userIds: [] })}>
          {Object.entries(m.audiences).map(([value, label]) => <NativeSelectOption key={value} value={value}>{label}</NativeSelectOption>)}
        </NativeSelect>
      </Field> : null}
      {action.type === "NOTIFY" && action.audience === "PROJECT_FIELD" ? <Field>
        <FieldLabel htmlFor={id + "-field"}>{messages.projectFields.fieldAudience}</FieldLabel>
        <NativeSelect id={id + "-field"} required value={action.fieldId ?? ""} onChange={(event) => onChange({ ...action, fieldId: event.target.value || null })}>
          <NativeSelectOption value="">{messages.projectFields.fieldAudience}</NativeSelectOption>
          {fields.filter((field) => field.type === "PERSON").map((field) => <NativeSelectOption key={field.id} value={field.id}>{field.label}</NativeSelectOption>)}
        </NativeSelect>
        {!fields.some((field) => field.type === "PERSON") ? <FieldDescription>{messages.projectFields.fieldAudienceEmpty}</FieldDescription> : null}
      </Field> : null}
      {action.type === "ASSIGN" || (action.type === "NOTIFY" && action.audience === "PEOPLE") ? <>
        <MultiPick label={action.type === "ASSIGN" ? m.assignees : m.people} options={eligible.map((member) => ({ id: member.userId, label: member.displayName ?? member.loginId, hint: member.loginId }))} value={action.userIds} onChange={(userIds) => onChange({ ...action, userIds })} />
        {action.type === "ASSIGN" ? <p className="text-xs text-muted-foreground">{m.clearAssignees}</p> : null}
      </> : null}
      {action.type === "ADD_LABELS" || action.type === "REMOVE_LABELS" ? <MultiPick label={m.labels} options={labels.map((label) => ({ id: label.id, label: label.name }))} value={action.labelIds} onChange={(labelIds) => onChange({ ...action, labelIds })} /> : null}
      {action.type === "NOTIFY" ? <>
        <Field>
          <div className="flex items-center gap-1">
            <FieldLabel htmlFor={`${id}-message`}>{m.message}</FieldLabel>
            <Tooltip>
              <TooltipTrigger render={<Button type="button" variant="ghost" size="icon-xs" aria-label={m.variableHelpTitle} />}>
                <CircleHelpIcon className="size-3.5 text-muted-foreground" aria-hidden="true" />
              </TooltipTrigger>
              <TooltipContent side="top" align="start" className="max-w-80 space-y-2 p-3 text-left leading-5">
                <p className="font-semibold">{m.variableHelpTitle}</p>
                <p>{m.variableHelpUsage}</p>
                <ul className="space-y-1">{m.variableHelpItems.map((item) => <li key={item.variable}><code>{`{${item.variable}}`}</code> — {item.description}</li>)}</ul>
                <p>{m.variableHelpTiming}</p>
              </TooltipContent>
            </Tooltip>
          </div>
          <Textarea id={`${id}-message`} value={action.message} placeholder={m.messagePlaceholder} maxLength={1000} rows={3} onChange={(event) => onChange({ ...action, message: event.target.value })} />
          <FieldDescription>{m.messageHint}</FieldDescription>
          <div className="flex flex-wrap gap-1">
            {MESSAGE_VARIABLES.map((variable) => <Button key={variable} type="button" size="xs" variant="outline" onClick={() => onChange({ ...action, message: action.message + `{${variable}}` })}>{`{${variable}}`}</Button>)}
          </div>
          <div><Button type="button" size="sm" variant="ghost" onClick={() => onChange({ ...action, message: template })}>{m.resetMessage}</Button></div>
          <p className="text-xs font-medium text-muted-foreground">{m.messagePreview}</p>
          <p className="whitespace-pre-wrap rounded-md border bg-muted/40 p-3 text-sm">{preview || m.emptyMessagePreview}</p>
        </Field>
      </> : null}
    </div>
  );
}

export function AutomationEditor({ initial, members, labels, fields, onSave, onCancel, pending, error }: {
  initial?: AutomationInput; members: ProjectMember[]; labels: Label[]; fields: ProjectIssueField[]; onSave: (input: AutomationInput) => void; onCancel: () => void; pending: boolean; error: unknown;
}) {
  const statuses = useIssueStatuses();
  const id = useId();
  const [input, setInput] = useState<AutomationInput>(() => initial ?? { name: "", enabled: true, fromStatusId: null, toStatusId: statuses.find((status) => status.id === "00000000-0000-7000-8000-0000000000a6")?.id ?? statuses[0].id, actions: [] });
  const messageFor = (value: AutomationInput) => suggestedMessage(statuses.find((status) => status.id === value.toStatusId), statuses.find((status) => status.id === value.fromStatusId));
  const changeTrigger = (changes: Partial<Pick<AutomationInput, "fromStatusId" | "toStatusId">>) => setInput((current) => {
    const next = { ...current, ...changes };
    const oldTemplate = messageFor(current);
    const template = messageFor(next);
    return { ...next, actions: current.actions.map((action) => action.type === "NOTIFY" && (!action.message.trim() || action.message === oldTemplate) ? { ...action, message: template } : action) };
  });
  const [dragged, setDragged] = useState<ActionType | null>(null);
  const sensors = useSensors(useSensor(MouseSensor, { activationConstraint: { distance: 6 } }), useSensor(TouchSensor, { activationConstraint: { delay: 250, tolerance: 8 } }));
  const setActions = (actions: AutomationAction[]) => setInput((current) => ({ ...current, actions }));
  const add = (type: ActionType, index = input.actions.length) => {
    if (input.actions.length >= 10) return;
    const actions = [...input.actions]; actions.splice(index, 0, { ...newAction(type), message: type === "NOTIFY" ? messageFor(input) : "" }); setActions(actions);
  };
  const end = ({ active, over }: DragEndEvent) => {
    setDragged(null);
    if (!over) return;
    const gap = over.data.current?.index as number;
    if (active.data.current?.source === "palette") add(active.data.current.type as ActionType, gap);
    else setActions(moveBlock(input.actions, String(active.id), gap));
  };
  return (
    <form className="flex flex-col gap-5 rounded-xl border bg-muted/30 p-4" onSubmit={(event) => { event.preventDefault(); onSave(input); }}>
      <Field><FieldLabel htmlFor={`${id}-name`}>{m.name}</FieldLabel><Input id={`${id}-name`} required maxLength={80} value={input.name} placeholder={m.namePlaceholder} onChange={(event) => setInput({ ...input, name: event.target.value })} /></Field>
      <CheckSetting id={`${id}-enabled`} label={m.enabled} checked={input.enabled} onChange={(enabled) => setInput({ ...input, enabled })} />
      <div className="flex flex-col gap-3 rounded-xl border bg-background p-4">
        <h4 className="flex items-center gap-2 text-sm font-medium"><WorkflowIcon className="size-4" />{m.trigger}</h4>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field><FieldLabel htmlFor={`${id}-from`}>{m.from}</FieldLabel><NativeSelect id={`${id}-from`} value={input.fromStatusId ?? ""} onChange={(event) => changeTrigger({ fromStatusId: event.target.value || null })}><NativeSelectOption value="">{m.any}</NativeSelectOption>{statuses.map((status) => <NativeSelectOption key={status.id} value={status.id}>{status.name}</NativeSelectOption>)}</NativeSelect></Field>
          <Field><FieldLabel htmlFor={`${id}-to`}>{m.to}</FieldLabel><NativeSelect id={`${id}-to`} required value={input.toStatusId} onChange={(event) => changeTrigger({ toStatusId: event.target.value })}>{statuses.map((status) => <NativeSelectOption key={status.id} value={status.id}>{status.name}</NativeSelectOption>)}</NativeSelect></Field>
        </div>
      </div>
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragStart={({ active }) => setDragged((active.data.current?.type ?? input.actions.find((action) => action.id === active.id)?.type) as ActionType)} onDragEnd={end} onDragCancel={() => setDragged(null)}>
        <div className="flex flex-col gap-2"><h4 className="text-sm font-medium">{m.palette}</h4><p className="text-xs text-muted-foreground">{m.paletteHint}</p><div className="flex flex-wrap gap-2">{actionTypes.map((type) => <PaletteBlock key={type} type={type} onAdd={() => add(type)} disabled={input.actions.length >= 10} />)}</div>{input.actions.length >= 10 ? <p className="text-xs text-muted-foreground">{m.actionLimit}</p> : null}</div>
        <div aria-label={m.pipeline} className="flex flex-col">
          {input.actions.map((action, index) => <div key={action.id}><Gap index={index} empty={false} /><ActionBlock template={messageFor(input)} preview={previewMessage(action.message, statuses.find((status) => status.id === input.toStatusId), statuses.find((status) => status.id === input.fromStatusId))} action={action} index={index} last={index === input.actions.length - 1} fields={fields} members={members} labels={labels} onChange={(next) => setActions(input.actions.map((current) => current.id === action.id ? next : current))} onRemove={() => setActions(input.actions.filter((current) => current.id !== action.id))} onMove={(direction) => setActions(moveBlock(input.actions, action.id, direction < 0 ? index - 1 : index + 2))} /></div>)}
          <Gap index={input.actions.length} empty={input.actions.length === 0} />
        </div>
        <DragOverlay>{dragged ? <div className="rounded-lg border bg-background p-3 text-sm shadow-lg">{m.actions[dragged]}</div> : null}</DragOverlay>
      </DndContext>
      {error ? <FormAlert>{apiErrorMessage(error)}</FormAlert> : null}
      <FieldDescription>{m.unsaved}</FieldDescription>
      <div className="flex gap-2"><Button type="submit" disabled={pending || input.actions.length === 0}>{pending ? m.saving : m.save}</Button><Button type="button" variant="ghost" onClick={onCancel}>{m.cancel}</Button></div>
    </form>
  );
}
