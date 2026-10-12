"use client";

import { useState } from "react";
import { PlusIcon } from "lucide-react";
import { personName } from "@/components/person";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import type { UserSummary } from "@/features/members/types";
import { apiErrorMessage } from "@/lib/api/error-message";
import { useRefreshingMutation } from "@/lib/use-refreshing-mutation";
import { messages } from "@/messages";
import { putProjectMember } from "../api";
import type { ProjectRole } from "../types";

const m = messages.projects;
const ROLES: ProjectRole[] = ["MANAGER", "EDITOR", "VIEWER"];

/**
 * The "+" on the members card of a project overview, for people who manage the project: pick
 * someone who is not in the project yet and the role to give them.
 */
export function AddProjectMemberButton({
  projectKey,
  candidates,
}: {
  projectKey: string;
  /** Active people who are not members of the project yet. */
  candidates: UserSummary[];
}) {
  const [open, setOpen] = useState(false);
  const [userId, setUserId] = useState("");
  const [role, setRole] = useState<ProjectRole>("EDITOR");
  const mutation = useRefreshingMutation(putProjectMember, () => {
    setUserId("");
    setOpen(false);
  });

  return (
    <>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        className="text-muted-foreground max-md:size-11"
        aria-label={m.addMemberTitle}
        title={m.addMemberTitle}
        onClick={() => {
          mutation.reset();
          setOpen(true);
        }}
      >
        <PlusIcon />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{m.addMemberTitle}</DialogTitle>
            <DialogDescription>{candidates.length > 0 ? m.addMemberDescription : m.noCandidates}</DialogDescription>
          </DialogHeader>
          {candidates.length > 0 ? (
            <form
              className="flex flex-col gap-4"
              onSubmit={(event) => {
                event.preventDefault();
                if (userId && !mutation.isPending) mutation.mutate({ key: projectKey, userId, role });
              }}
            >
              {mutation.isError ? <FormAlert>{apiErrorMessage(mutation.error)}</FormAlert> : null}
              <FieldGroup>
                <Field>
                  <FieldLabel htmlFor="overview-member-user">{m.addMemberLabel}</FieldLabel>
                  <NativeSelect
                    id="overview-member-user"
                    className="max-md:h-11"
                    value={userId}
                    onChange={(event) => setUserId(event.target.value)}
                  >
                    <NativeSelectOption value="">{m.addMemberPlaceholder}</NativeSelectOption>
                    {candidates.map((person) => (
                      <NativeSelectOption key={person.id} value={person.id}>
                        {personName(person)}
                      </NativeSelectOption>
                    ))}
                  </NativeSelect>
                </Field>
                <Field>
                  <FieldLabel htmlFor="overview-member-role">{m.addMemberRoleLabel}</FieldLabel>
                  <NativeSelect
                    id="overview-member-role"
                    className="max-md:h-11"
                    value={role}
                    onChange={(event) => setRole(event.target.value as ProjectRole)}
                  >
                    {ROLES.map((item) => (
                      <NativeSelectOption key={item} value={item}>
                        {messages.roles[item]}
                      </NativeSelectOption>
                    ))}
                  </NativeSelect>
                </Field>
              </FieldGroup>
              <Button type="submit" className="max-md:h-11" disabled={!userId || mutation.isPending}>
                {mutation.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
                {m.addMember}
              </Button>
            </form>
          ) : null}
        </DialogContent>
      </Dialog>
    </>
  );
}
