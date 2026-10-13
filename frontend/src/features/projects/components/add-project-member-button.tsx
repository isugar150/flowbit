"use client";

import { useState } from "react";
import { PlusIcon } from "lucide-react";
import { MultiCombobox } from "@/components/multi-combobox";
import { PersonAvatar, personName } from "@/components/person";
import { Button } from "@/components/ui/button";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import type { UserSummary } from "@/features/members/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { putProjectMember } from "../api";
import type { ProjectRole } from "../types";

const m = messages.projects;
const ROLES: ProjectRole[] = ["MANAGER", "EDITOR", "VIEWER"];

/**
 * The "+" on the members card of a project overview, for people who manage the project. It opens
 * the same kind of list as "라벨 추가": type to find someone who is not in the project yet and
 * pick them, and they are in. The list stays open for the next person.
 */
export function AddProjectMemberButton({
  projectId,
  candidates,
}: {
  projectId: string;
  /** Active people who are not members of the project yet. */
  candidates: UserSummary[];
}) {
  const [role, setRole] = useState<ProjectRole>("EDITOR");
  const mutation = useRefreshingMutation(putProjectMember);

  return (
    <Popover onOpenChange={() => mutation.reset()}>
      <PopoverTrigger
        render={
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            className="text-muted-foreground max-md:size-11"
            aria-label={m.addMemberTitle}
            title={m.addMemberTitle}
          />
        }
      >
        <PlusIcon />
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 p-0">
        <MultiCombobox
          options={candidates.map((person) => ({
            id: person.id,
            label: personName(person),
            hint: person.displayName ? person.loginId : undefined,
            icon: <PersonAvatar person={person} />,
          }))}
          // Nobody in the list is in the project: someone who is added leaves the list.
          value={[]}
          onChange={([userId]) => {
            if (userId && !mutation.isPending) mutation.mutate({ id: projectId, userId, role });
          }}
          placeholder={m.memberSearchPlaceholder}
          listLabel={m.memberCandidates}
          emptyText={m.noCandidates}
          error={mutation.isError ? apiErrorMessage(mutation.error) : null}
          maxLength={254}
          footer={
            <label className="flex items-center justify-between gap-2 text-[0.8125rem] leading-5 text-muted-foreground">
              {m.addMemberRoleOf}
              <NativeSelect
                size="sm"
                className="w-20 max-md:h-11"
                value={role}
                onChange={(event) => setRole(event.target.value as ProjectRole)}
              >
                {ROLES.map((item) => (
                  <NativeSelectOption key={item} value={item}>
                    {messages.roles[item]}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </label>
          }
        />
      </PopoverContent>
    </Popover>
  );
}
