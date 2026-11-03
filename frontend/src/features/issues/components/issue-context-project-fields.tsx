"use client";

import { useRouter } from "next/navigation";
import { PropertyTypeIcon, PropertyValue } from "@/features/custom-properties/components/custom-properties";
import type { CustomProperty } from "@/features/custom-properties/types";
import { useCustomProperties } from "@/features/custom-properties/use-custom-properties";
import { changeProjectField } from "@/features/project-fields/api";
import type { ProjectMember } from "@/features/projects/types";
import { personName } from "@/components/person";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { recordUndo } from "../undo";
import type { Issue } from "../types";
import { Choices } from "./issue-context-choices";

function summary(item: CustomProperty): string {
  if (item.type === "PERSON") return item.people?.map(personName).join(", ") || (item.person ? personName(item.person) : messages.customProperties.emptyValue);
  if (item.type === "CHECKBOX") return item.value ? messages.issueContext.checked : messages.issueContext.unchecked;
  return item.value === null ? messages.customProperties.emptyValue : String(item.value);
}

/** Common fields keep the same typed editors, validation, permissions and save path as the issue detail. */
export function IssueContextProjectFields({ issue, members, open, allowed }: { issue: Issue; members: ProjectMember[] | undefined; open: boolean; allowed: boolean }) {
  const router = useRouter();
  const properties = useCustomProperties({ type: "issue", id: issue.id }, `${issue.version}:${issue.project?.id ?? ""}`, open && Boolean(issue.project) && allowed);
  if (!issue.project) return null;
  if (properties.query.isError) return <p role="alert" className="px-2 py-1 text-sm text-destructive">{apiErrorMessage(properties.query.error)}</p>;
  if (!properties.query.data) return <p role="status" className="px-2 py-1 text-sm text-muted-foreground">{messages.issueContext.loadingFields}</p>;
  const list = properties.query.data;
  const fields = list.items.filter((item) => item.projectFieldId);
  if (!fields.length) return null;
  return <>
    <div className="my-1 border-t" role="separator" />
    <div className="px-2 py-1 text-xs text-muted-foreground">{messages.issueContext.projectFields}</div>
    {properties.error ? <p role="alert" className="px-2 py-1 text-sm text-destructive">{apiErrorMessage(properties.error)}</p> : null}
    {fields.map((item) => (
      <Choices key={item.id} label={item.label} icon={<PropertyTypeIcon type={item.type} />} value={summary(item)} disabled={!list.canEdit || properties.query.isFetching || (item.type === "PERSON" && !members)}>
        <div className="p-2" onKeyDown={(event) => {
          if ((event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) && event.key !== "Escape" && event.key !== "Tab") event.stopPropagation();
        }}>
          <PropertyValue
            item={item}
            canEdit={allowed && list.canEdit && !properties.query.isFetching && !properties.change.isPending}
            plain
            members={members ?? []}
            onChange={(changes, person) => {
              const previous = item.value;
              properties.change.mutate({ id: item.id, changes, person }, {
                onSuccess: () => {
                  recordUndo(`${issue.key} ${item.label} 변경`, async () => {
                    await changeProjectField(issue.id, item.projectFieldId ?? item.id, previous);
                    await properties.query.refetch();
                    router.refresh();
                  });
                  router.refresh();
                },
              });
            }}
          />
        </div>
      </Choices>
    ))}
  </>;
}
