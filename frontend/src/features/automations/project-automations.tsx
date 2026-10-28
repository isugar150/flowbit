"use client";

import { getProjectFields, projectFieldsKey } from "@/features/project-fields/api";
import { useState } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { PlusIcon, RefreshCwIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TimeAgo } from "@/components/dates";
import { ListRowsSkeleton } from "@/components/skeletons";
import { FormAlert } from "@/features/auth/components/form-alert";
import { useProjectApiScope } from "@/features/projects/project-api-scope";
import type { ProjectMember } from "@/features/projects/types";
import type { Label } from "@/features/issues/types";
import { useIssueStatuses } from "@/features/issues/statuses";
import { apiFetch } from "@/lib/api/client";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { deleteAutomation, getAutomations, getAutomationRuns, saveAutomation } from "./api";
import { AutomationEditor } from "./automation-editor";
import type { AutomationInput, AutomationRule } from "./types";

const m = messages.automations;

export function ProjectAutomations({ projectId, members }: { projectId: string; members: ProjectMember[] }) {
  const scope = useProjectApiScope();
  const client = useQueryClient();
  const statuses = useIssueStatuses();
  const [editing, setEditing] = useState<AutomationRule | "new" | null>(null);
  const ruleKey = ["automations", projectId, scope];
  const runKey = ["automation-runs", projectId, scope];
  const rules = useQuery({ queryKey: ruleKey, queryFn: () => getAutomations(projectId, scope) });
  const runs = useQuery({ queryKey: runKey, queryFn: () => getAutomationRuns(projectId, scope) });
  const fields = useQuery({ queryKey: projectFieldsKey(projectId, scope), queryFn: () => getProjectFields(projectId, scope) });
  const labels = useQuery({ queryKey: ["labels"], queryFn: () => apiFetch<Label[]>("/api/v1/labels") });
  const save = useRefreshingMutation((input: AutomationInput) => saveAutomation(projectId, scope, typeof editing === "object" && editing ? editing.id : undefined, input), (rule) => {
    client.setQueryData<AutomationRule[]>(ruleKey, (current = []) => current.some((item) => item.id === rule.id) ? current.map((item) => item.id === rule.id ? rule : item) : [...current, rule]);
    setEditing(null);
  });
  const remove = useRefreshingMutation((id: string) => deleteAutomation(projectId, scope, id).then(() => id), (id) => {
    client.setQueryData<AutomationRule[]>(ruleKey, (current = []) => current.filter((item) => item.id !== id));
    if (typeof editing === "object" && editing?.id === id) setEditing(null);
  });
  const statusName = (id: string | null) => id ? statuses.find((status) => status.id === id)?.name ?? m.missing : m.any;
  if (rules.isPending) return <ListRowsSkeleton rows={3} label={m.loading} />;
  if (rules.error || labels.error || fields.error) return <FormAlert>{apiErrorMessage(rules.error ?? labels.error ?? fields.error)}</FormAlert>;
  const refresh = () => { void rules.refetch(); void runs.refetch(); };
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap gap-2"><Button variant="outline" size="sm" onClick={() => setEditing("new")}><PlusIcon />{m.newRule}</Button><Button variant="ghost" size="sm" onClick={refresh}><RefreshCwIcon />{m.refresh}</Button></div>
      {rules.data?.length === 0 && !editing ? <p className="text-sm text-muted-foreground">{m.empty}</p> : null}
      <ul className="flex flex-col gap-2">
        {rules.data?.map((rule) => <li key={rule.id} className="flex flex-wrap items-center gap-2 rounded-lg border p-3">
          <div className="mr-auto min-w-0 flex-1"><p className="break-words text-sm font-medium">{rule.name}</p><p className="text-xs text-muted-foreground">{statusName(rule.fromStatusId)} → {statusName(rule.toStatusId)} · {m.count(rule.actions.length)} · {rule.enabled ? m.on : m.off}</p></div>
          <Button variant="ghost" size="sm" onClick={() => setEditing(rule)}>{m.edit}</Button>
          <Button variant="ghost" size="sm" disabled={remove.isPending} onClick={() => { if (window.confirm(m.confirmDelete)) remove.mutate(rule.id); }}>{m.remove}</Button>
        </li>)}
      </ul>
      {remove.error ? <FormAlert>{apiErrorMessage(remove.error)}</FormAlert> : null}
      {editing ? <AutomationEditor key={editing === "new" ? "new" : editing.id} initial={editing === "new" ? undefined : editing} members={members} fields={fields.data ?? []} labels={labels.data ?? []} onSave={(input) => save.mutate(input)} onCancel={() => setEditing(null)} pending={save.isPending} error={save.error} /> : null}
      <details className="rounded-lg border p-3">
        <summary className="cursor-pointer text-sm font-medium">{m.runs}</summary>
        <div className="mt-3 flex flex-col gap-3">
          <p className="text-xs text-muted-foreground">{m.runsHint}</p>
          {runs.error ? <FormAlert>{apiErrorMessage(runs.error)}</FormAlert> : null}
          {runs.isPending ? <p className="text-sm text-muted-foreground">{m.loading}</p> : runs.data?.length === 0 ? <p className="text-sm text-muted-foreground">{m.noRuns}</p> : null}
          <ul className="flex flex-col gap-3">{runs.data?.map((run) => <li key={run.id} className="flex flex-col gap-1 border-t pt-3 text-xs">
            <p className="font-medium">{run.ruleName} · <TimeAgo iso={run.createdAt} /></p>
            {scope === "project" ? <Link href={`/issue/${run.issueKey}`} className="underline underline-offset-4">{run.issueKey} {run.issueTitle}</Link> : <p>#{run.issueNumber} {run.issueTitle}</p>}
            <ol className="list-inside list-decimal text-muted-foreground">{run.results.map((result, index) => <li key={index}>{result.message}</li>)}</ol>
          </li>)}</ul>
        </div>
      </details>
    </div>
  );
}
