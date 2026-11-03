"use client";

import { useState, type ReactElement, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { ContextMenu } from "@base-ui/react/context-menu";
import { useQuery } from "@tanstack/react-query";
import { CalendarIcon, CircleDotIcon, FolderIcon, GaugeIcon, RepeatIcon, SignalHighIcon, TagIcon, UserIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { DropdownMenuItem, DropdownMenuCheckboxItem, DropdownMenuSub, DropdownMenuSubTrigger, DropdownMenuSubContent } from "@/components/ui/dropdown-menu";
import { IssueViewing } from "@/features/realtime/components/issue-viewing";
import { personName } from "@/components/person";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { UserSummary } from "@/features/members/types";
import { usersAsMembers, type ProjectMember, type ProjectSummary } from "@/features/projects/types";
import { getCycles, cycleSpan } from "@/features/cycles/api";
import { apiFetch } from "@/lib/api/client";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { priorityOptions, estimateOptions, MAX_ASSIGNEES, NONE } from "../options";
import { StatusIcon } from "../meta";
import { useIssueStatuses } from "../statuses";
import { updateIssueUndoable, type UndoableChange } from "../undoable";
import type { Issue, IssueChanges, IssueDetail, Label } from "../types";

const m = messages.issues;
const text = messages.issueContext;

function Choices({ label, icon, children, disabled }: { label: string; icon: ReactNode; children: ReactNode; disabled?: boolean }) {
  return <DropdownMenuSub><DropdownMenuSubTrigger disabled={disabled}>{icon}{label}</DropdownMenuSubTrigger><DropdownMenuSubContent className="max-h-80 w-64">{children}</DropdownMenuSubContent></DropdownMenuSub>;
}

function Choice({ checked, children, onClick, disabled }: { checked: boolean; children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return <DropdownMenuCheckboxItem checked={checked} disabled={disabled} closeOnClick onCheckedChange={onClick}>{children}</DropdownMenuCheckboxItem>;
}

type ContextChange = UndoableChange & { next: Issue };

/** Queries and editors are mounted only after this row's first context-menu interaction. */
export function IssueContextMenu({ issue, editable, children, onStatusChange }: { issue: Issue; editable: boolean; onStatusChange?: (number: number, statusId: string) => void; children: (issue: Issue) => ReactElement<{ children?: ReactNode }> }) {
  const [open, setOpen] = useState(false);
  const [used, setUsed] = useState(false);
  const mutation = useOptimisticMutation(issue, (_current: Issue, input: ContextChange) => input.next, updateIssueUndoable);
  if (!editable || issue.deletedAt) return <IssueViewing number={issue.number}>{children(issue)}</IssueViewing>;
  const trigger = children(mutation.value);
  return <ContextMenu.Root open={open} onOpenChange={(next) => { setOpen(next); if (next) setUsed(true); }}>
    <IssueViewing number={issue.number}><ContextMenu.Trigger render={trigger}>{trigger.props.children}</ContextMenu.Trigger></IssueViewing>
    {used ? <IssueContextMenuBody saved={mutation.value} open={open} pending={mutation.isPending} error={mutation.error} mutate={mutation.mutate} reset={mutation.reset} onStatusChange={onStatusChange} /> : null}
  </ContextMenu.Root>;
}

function IssueContextMenuBody({ saved, open, pending, error, mutate, reset, onStatusChange }: { saved: Issue; open: boolean; pending: boolean; error: unknown; mutate: (input: ContextChange) => void; reset: () => void; onStatusChange?: (number: number, statusId: string) => void }) {
  const [dates, setDates] = useState<{ start: string; end: string } | null>(null);
  const statuses = useIssueStatuses();
  const detail = useQuery({ queryKey: ["issue-context", saved.number, saved.version], queryFn: () => apiFetch<IssueDetail>(`/api/v1/issues/${saved.number}`), enabled: open, staleTime: 0 });
  const base = !pending && detail.data && detail.data.version > saved.version ? detail.data : saved;
  const projectId = base.project?.id;
  const members = useQuery({
    queryKey: ["issue-context-members", projectId ?? null],
    queryFn: () => projectId ? apiFetch<ProjectMember[]>(`/api/v1/projects/${projectId}/members`) : apiFetch<UserSummary[]>("/api/v1/users").then(usersAsMembers),
    enabled: open && detail.data?.canEdit === true,
    staleTime: 0,
  });
  const labels = useQuery({ queryKey: ["issue-context-labels"], queryFn: () => apiFetch<Label[]>("/api/v1/labels"), enabled: open, staleTime: 0 });
  const projects = useQuery({ queryKey: ["issue-context-projects"], queryFn: () => apiFetch<ProjectSummary[]>("/api/v1/projects"), enabled: open, staleTime: 0 });
  const cycles = useQuery({ queryKey: ["cycles", projectId], queryFn: () => getCycles(projectId as string), enabled: open && Boolean(projectId) && detail.data?.canEdit === true, staleTime: 0 });
  const apply = (current: Issue, changes: IssueChanges): Issue => {
    const status = statuses.find((item) => item.id === changes.statusId);
    const people = [...(members.data ?? []).map((member) => ({ ...member, id: member.userId })), ...current.assignees];
    const project = projects.data?.find((item) => item.id === changes.projectId);
    const cycle = [cycles.data?.current, cycles.data?.upcoming, ...(cycles.data?.past ?? [])].find((item) => item?.id === changes.cycleId);
    return {
      ...current, ...changes,
      ...(status ? { status: status.category, statusId: status.id, statusName: status.name } : {}),
      ...(changes.assigneeIds ? { assignees: changes.assigneeIds.flatMap((id) => people.find((person) => person.id === id) ?? []) } : {}),
      ...(changes.labelIds ? { labels: (labels.data ?? current.labels).filter((label) => changes.labelIds?.includes(label.id)) } : {}),
      ...("projectId" in changes ? { project: project ? { id: project.id, name: project.name } : null, cycle: null } : {}),
      ...("cycleId" in changes ? { cycle: cycle ?? null } : {}),
    };
  };
  const issue = base;
  const allowed = detail.data?.canEdit === true && !detail.data.deletedAt && !detail.isFetching;
  const change = (changes: IssueChanges) => {
    if (!allowed || pending) return;
    const previous: IssueChanges = {
      statusId: issue.statusId, priority: issue.priority, assigneeIds: issue.assignees.map((person) => person.id),
      labelIds: issue.labels.map((label) => label.id), projectId: issue.project?.id ?? null,
      startDate: issue.startDate, dueDate: issue.dueDate, estimate: issue.estimate, cycleId: issue.cycle?.id ?? null,
    };
    const undoWith = Object.fromEntries(Object.keys(changes).map((key) => [key, previous[key as keyof IssueChanges]])) as IssueChanges;
    if (Object.entries(changes).every(([field, value]) => JSON.stringify(previous[field as keyof IssueChanges]) === JSON.stringify(value))) return;
    if (changes.statusId && onStatusChange) { onStatusChange(issue.number, changes.statusId); return; }
    if ("projectId" in changes) undoWith.cycleId = previous.cycleId;
    mutate({ number: issue.number, key: issue.key, changes, undoWith, next: apply(issue, changes) });
  };
  const failure = error ?? detail.error ?? members.error ?? labels.error ?? projects.error ?? cycles.error;
  const people = [...(members.data ?? []), ...issue.assignees.filter((person) => !members.data?.some((member) => member.userId === person.id)).map((person) => ({ ...person, userId: person.id }))];
  const cycleChoices = [cycles.data?.current, cycles.data?.upcoming, ...(cycles.data?.past ?? [])].filter((item) => item != null);
  if (issue.cycle && !cycleChoices.some((cycle) => cycle.id === issue.cycle?.id)) cycleChoices.push({ ...issue.cycle, total: 0, done: 0 });
  return <>
      <ContextMenu.Portal><ContextMenu.Positioner className="z-50" sideOffset={4}><ContextMenu.Popup aria-label={text.menuOf(issue.key)} className="max-h-(--available-height) w-56 overflow-y-auto rounded-lg bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10 outline-none">
        <div className="truncate px-2 py-1 text-xs text-muted-foreground">{issue.key}</div>
        {failure ? <div role="alert" className="px-2 py-1 text-sm text-destructive">{apiErrorMessage(failure)}</div> : null}
        {detail.isFetching ? <div role="status" className="px-2 py-1 text-sm text-muted-foreground">{text.loading}</div> : null}
        {detail.data && !detail.data.canEdit ? <div className="px-2 py-1 text-sm text-muted-foreground">{text.readOnly}</div> : null}
        {pending ? <div role="status" className="px-2 py-1 text-sm text-muted-foreground">{text.saving}</div> : null}
        {allowed && !pending ? <ContextMenu.Group>
          <Choices label={m.status} icon={<CircleDotIcon />}>
            {statuses.map((status) => <Choice key={status.id} checked={issue.statusId === status.id} onClick={() => change({ statusId: status.id })}><StatusIcon status={status.category} statusId={status.id} />{status.name}</Choice>)}
          </Choices>
          <Choices label={m.priority} icon={<SignalHighIcon />}>
            {priorityOptions.map((option) => <Choice key={option.value} checked={issue.priority === option.value} onClick={() => change({ priority: option.value })}>{option.icon}{option.label}</Choice>)}
          </Choices>
          <Choices label={m.assignee} icon={<UserIcon />} disabled={!members.data}>
            <Choice checked={issue.assignees.length === 0} onClick={() => change({ assigneeIds: [] })}>{m.noAssignee}</Choice>
            {people.map((person) => {
              const selected = issue.assignees.some((current) => current.id === person.userId);
              return <Choice key={person.userId} checked={selected} disabled={!selected && issue.assignees.length >= MAX_ASSIGNEES} onClick={() => change({ assigneeIds: selected ? issue.assignees.filter((current) => current.id !== person.userId).map((current) => current.id) : [...issue.assignees.map((current) => current.id), person.userId] })}><span className="truncate">{personName(person)} <span className="text-xs text-muted-foreground">{person.loginId}</span></span></Choice>;
            })}
          </Choices>
          <Choices label={m.labels} icon={<TagIcon />} disabled={!labels.data}>
            <Choice checked={issue.labels.length === 0} onClick={() => change({ labelIds: [] })}>{m.noLabels}</Choice>
            {labels.data?.map((label) => <Choice key={label.id} checked={issue.labels.some((current) => current.id === label.id)} onClick={() => change({ labelIds: issue.labels.some((current) => current.id === label.id) ? issue.labels.filter((current) => current.id !== label.id).map((current) => current.id) : [...issue.labels.map((current) => current.id), label.id] })}><span className="truncate">{label.name}</span></Choice>)}
          </Choices>
          <Choices label={m.project} icon={<FolderIcon />} disabled={Boolean(issue.parent) || !projects.data}>
            <Choice checked={!issue.project} onClick={() => change({ projectId: null })}>{m.noProject}</Choice>
            {projects.data?.filter((project) => project.myRole && project.myRole !== "VIEWER").map((project) => <Choice key={project.id} checked={issue.project?.id === project.id} onClick={() => change({ projectId: project.id })}><span className="truncate">{project.name}</span></Choice>)}
          </Choices>
          <DropdownMenuItem onClick={() => setDates({ start: issue.startDate ?? "", end: issue.dueDate ?? "" })}><CalendarIcon />{m.dueDate}</DropdownMenuItem>
          {projects.data?.some((project) => project.id === projectId && project.estimatesEnabled) ? <Choices label={m.estimate} icon={<GaugeIcon />}>
            {estimateOptions(issue.estimate).map((option) => <Choice key={option.value} checked={(issue.estimate === null ? NONE : String(issue.estimate)) === option.value} onClick={() => change({ estimate: option.value === NONE ? null : Number(option.value) })}>{option.label}</Choice>)}
          </Choices> : null}
          {cycles.data?.settings.enabled ? <Choices label={messages.cycles.property} icon={<RepeatIcon />}>
            <Choice checked={!issue.cycle} onClick={() => change({ cycleId: null })}>{messages.cycles.none}</Choice>
            {cycleChoices.map((cycle) => <Choice key={cycle.id} checked={issue.cycle?.id === cycle.id} onClick={() => change({ cycleId: cycle.id })}>{messages.cycles.option("", cycle.number, cycleSpan(cycle))}</Choice>)}
          </Choices> : null}
        </ContextMenu.Group> : null}
      </ContextMenu.Popup></ContextMenu.Positioner></ContextMenu.Portal>
    <Dialog open={dates !== null} onOpenChange={(next) => { if (!next) setDates(null); }}>
      <DialogContent>
        <DialogTitle>{text.datesOf(issue.key)}</DialogTitle>
        {failure ? <FormAlert>{apiErrorMessage(failure)}</FormAlert> : null}
        <form className="flex flex-col gap-3" onSubmit={(event) => { event.preventDefault(); if (dates) { change({ startDate: dates.start || null, dueDate: dates.end || null }); setDates(null); } }}>
          <label className="flex flex-col gap-1">{text.startDate}<Input type="date" value={dates?.start ?? ""} max={dates?.end || undefined} onChange={(event) => setDates((current) => current ? { ...current, start: event.target.value } : null)} /></label>
          <label className="flex flex-col gap-1">{m.dueDate}<Input type="date" required={Boolean(dates?.start)} min={dates?.start || undefined} value={dates?.end ?? ""} onChange={(event) => setDates((current) => current ? { ...current, end: event.target.value } : null)} /></label>
          <div className="flex justify-end gap-2"><Button type="button" variant="ghost" disabled={pending || !allowed} onClick={() => { change({ startDate: null, dueDate: null }); setDates(null); }}>{messages.dates.clear}</Button><Button type="submit" disabled={pending || !allowed}>{text.save}</Button></div>
        </form>
      </DialogContent>
    </Dialog>
    {error && !open && !dates ? createPortal(<div role="alert" className="fixed right-4 bottom-4 z-50 max-w-sm rounded-lg border bg-popover p-3 text-sm text-destructive">{apiErrorMessage(error)}<Button variant="ghost" size="sm" onClick={reset}>{text.dismiss}</Button></div>, document.body) : null}
  </>;
}
