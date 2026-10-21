"use client";

import { useState } from "react";
import { SearchIcon } from "lucide-react";
import { TimeAgo } from "@/components/dates";
import { personName } from "@/components/person";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from "@/components/ui/input-group";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage } from "@/lib/api/error-message";
import { initials } from "@/lib/initials";
import { useOptimisticMutation } from "@/lib/use-refreshing-mutation";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { updateMember } from "../api";
import type { Member, MemberStatus, WorkspaceRole } from "../types";
import { CreateMemberDialog } from "./create-member-dialog";
import { MemberProjectRoles } from "./member-project-roles";
import { ResetPasswordDialog } from "./reset-password-dialog";

const m = messages.members;

function MemberAvatar({ member, size }: { member: Member; size?: "lg" }) {
  return (
    <Avatar size={size}>
      {member.avatarUrl ? <AvatarImage src={member.avatarUrl} alt="" /> : null}
      <AvatarFallback>{initials(personName(member))}</AvatarFallback>
    </Avatar>
  );
}

/** One titled block of the detail pane. */
function Section({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="flex flex-col gap-3 px-5 py-4">
      <div className="flex flex-col gap-0.5">
        <h4 className="text-[0.8125rem] leading-5 font-semibold">{title}</h4>
        {description ? (
          <p className="text-[0.8125rem] leading-5 text-muted-foreground">
            {description}
          </p>
        ) : null}
      </div>
      {children}
    </section>
  );
}

/** A setting on one line: what it is on the left, the control on the right. */
function ActionRow({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
      <div className="flex min-w-0 flex-1 basis-56 flex-col gap-0.5">
        <span className="text-[0.8125rem] leading-5 font-medium">{title}</span>
        <span className="text-[0.8125rem] leading-5 text-muted-foreground">
          {description}
        </span>
      </div>
      {children}
    </div>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex min-h-7 items-center gap-3 text-[0.8125rem] leading-5">
      <dt className="w-24 shrink-0 text-muted-foreground">{label}</dt>
      <dd className="min-w-0 truncate">{children}</dd>
    </div>
  );
}

/**
 * Everyone who can sign in, with the controls administrators have over them: the people on the
 * left, the chosen person's settings on the right.
 */
export function MembersPanel({
  members: saved,
  currentUserId,
  ldapEnabled,
}: {
  members: Member[];
  currentUserId: string;
  /** With LDAP on, people arrive by signing in and their passwords live in the directory. */
  ldapEnabled: boolean;
}) {
  // A picked role, or turning someone off or on, shows right away and is saved behind it.
  const mutation = useOptimisticMutation(
    saved,
    (members, { id, ...changes }: { id: string; role?: WorkspaceRole; status?: MemberStatus }) =>
      members.map((member) => (member.id === id ? { ...member, ...changes } : member)),
    updateMember,
  );
  const members = mutation.value;
  // People whose account is disabled go last, and drop there the moment they are turned off; the order is otherwise the server's.
  const ordered = [...members].sort((a, b) => Number(a.status === "DISABLED") - Number(b.status === "DISABLED"));
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState(currentUserId);
  const needle = query.trim().toLowerCase();
  const shown = needle
    ? ordered.filter((member) =>
        [member.displayName, member.loginId, member.email].some((value) =>
          value?.toLowerCase().includes(needle),
        ),
      )
    : ordered;
  // The chosen person stays open while the list is searched; a removed one falls back to the first.
  const selected =
    members.find((member) => member.id === selectedId) ?? ordered[0];

  return (
    // data-wide: the administrator layout gives this page a wider column (admin/layout.tsx).
    <div data-wide className="flex flex-col gap-4">
      {ldapEnabled ? (
        <p className="rounded-lg border bg-muted/40 px-3 py-2 text-[0.8125rem] leading-5 text-muted-foreground">
          {m.ldapNotice}
        </p>
      ) : (
        <div className="flex justify-end">
          <CreateMemberDialog />
        </div>
      )}
      <div className="grid items-start gap-4 md:grid-cols-[17rem_minmax(0,1fr)]">
        <div className="flex flex-col gap-2">
          <InputGroup className="h-9 max-md:h-11">
            <InputGroupAddon>
              <SearchIcon />
            </InputGroupAddon>
            <InputGroupInput
              type="search"
              aria-label={m.search}
              placeholder={m.search}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </InputGroup>
          <p className="px-1 text-[0.8125rem] leading-5 text-muted-foreground">
            {needle ? m.countOf(shown.length, members.length) : m.count(members.length)}
          </p>
          {shown.length === 0 ? (
            <p className="rounded-xl border px-3 py-6 text-center text-[0.8125rem] leading-5 text-muted-foreground">
              {m.noMatch}
            </p>
          ) : (
            <ul className="flex flex-col gap-0.5 overflow-y-auto rounded-xl border p-1 max-md:max-h-64 md:max-h-[34rem]">
              {shown.map((member) => {
                const current = member.id === selected?.id;
                const disabled = member.status === "DISABLED";
                return (
                  <li key={member.id}>
                    <button
                      type="button"
                      aria-current={current ? "true" : undefined}
                      className={cn(
                        "flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left outline-none hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 max-md:min-h-11",
                        current && "bg-muted hover:bg-muted",
                      )}
                      onClick={() => {
                        setSelectedId(member.id);
                        mutation.reset();
                      }}
                    >
                      <span className={disabled ? "opacity-50" : undefined}>
                        <MemberAvatar member={member} />
                      </span>
                      <span className="flex min-w-0 flex-1 flex-col">
                        <span
                          className={cn(
                            "truncate text-[0.8125rem] leading-5 font-medium",
                            disabled && "text-muted-foreground",
                          )}
                        >
                          {personName(member)}
                        </span>
                        <span className="truncate text-xs leading-4 text-muted-foreground">
                          {disabled
                            ? m.disabled
                            : messages.roles[member.role]}
                          {member.id === currentUserId ? ` · ${m.you}` : ""}
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
        {selected ? (
          <MemberDetail
            // A new person starts with their own dialogs and project list.
            key={selected.id}
            member={selected}
            isSelf={selected.id === currentUserId}
            ldapEnabled={ldapEnabled}
            error={mutation.isError ? apiErrorMessage(mutation.error) : null}
            onChange={(changes) => mutation.mutate({ id: selected.id, ...changes })}
          />
        ) : null}
      </div>
    </div>
  );
}

function MemberDetail({
  member,
  isSelf,
  ldapEnabled,
  error,
  onChange,
}: {
  member: Member;
  isSelf: boolean;
  ldapEnabled: boolean;
  error: string | null;
  onChange: (changes: { role?: WorkspaceRole; status?: Member["status"] }) => void;
}) {
  const name = personName(member);
  const disabled = member.status === "DISABLED";
  // With LDAP on only administrators keep a password here; otherwise only local accounts have one.
  const canResetPassword = ldapEnabled
    ? member.role === "ADMIN"
    : member.authSource === "LOCAL";

  return (
    <article
      aria-label={name}
      className="flex min-w-0 flex-col divide-y rounded-xl border bg-card"
    >
      <header className="flex items-center gap-3 px-5 py-4">
        <MemberAvatar member={member} size="lg" />
        <div className="flex min-w-0 flex-col">
          <h3 className="flex min-w-0 items-center gap-2">
            <span className="truncate text-[0.9375rem] leading-[1.375rem] font-semibold">
              {name}
            </span>
            {isSelf ? <Badge variant="secondary">{m.you}</Badge> : null}
            {disabled ? <Badge variant="outline">{m.disabled}</Badge> : null}
          </h3>
          <span className="truncate text-[0.8125rem] leading-5 text-muted-foreground">
            {member.email ?? member.loginId}
          </span>
        </div>
      </header>
      {error ? (
        <div className="px-5 py-4">
          <FormAlert>{error}</FormAlert>
        </div>
      ) : null}
      <Section title={m.sections.account}>
        <dl className="flex flex-col">
          <Fact label={m.facts.loginId}>{member.loginId}</Fact>
          <Fact label={m.facts.email}>{member.email ?? m.none}</Fact>
          <Fact label={m.facts.authSource}>
            {m.authSourceNames[member.authSource]}
          </Fact>
          <Fact label={m.facts.lastLogin}>
            {member.lastLoginAt ? (
              <TimeAgo iso={member.lastLoginAt} />
            ) : (
              m.neverSignedIn
            )}
          </Fact>
          <Fact label={m.facts.createdAt}>
            <time dateTime={member.createdAt}>{member.createdAt.slice(0, 10)}</time>
          </Fact>
        </dl>
      </Section>
      <Section title={m.sections.access}>
        <ActionRow
          title={m.workspaceRole}
          description={isSelf ? m.roleSelfHint : m.roleHint}
        >
          <NativeSelect
            size="sm"
            className="w-28 max-md:h-11"
            aria-label={m.roleOf(name)}
            value={member.role}
            // Administrators cannot change themselves, so one always remains.
            disabled={isSelf}
            onChange={(event) =>
              onChange({ role: event.target.value as WorkspaceRole })
            }
          >
            <NativeSelectOption value="MEMBER">
              {messages.roles.MEMBER}
            </NativeSelectOption>
            <NativeSelectOption value="ADMIN">
              {messages.roles.ADMIN}
            </NativeSelectOption>
          </NativeSelect>
        </ActionRow>
      </Section>
      <Section title={m.projectRoles} description={m.projectRolesDescription}>
        <MemberProjectRoles memberId={member.id} />
      </Section>
      {canResetPassword || !isSelf ? (
        <Section title={m.sections.manage}>
          {canResetPassword ? (
            <ActionRow title={m.resetPassword} description={m.resetHint}>
              <ResetPasswordDialog memberId={member.id} memberName={name} />
            </ActionRow>
          ) : null}
          {isSelf ? null : (
            <ActionRow
              title={disabled ? m.enable : m.disable}
              description={disabled ? m.enableHint : m.disableHint}
            >
              <Button
                variant="outline"
                size="sm"
                className="max-md:h-11"
                onClick={() =>
                  onChange({ status: disabled ? "ACTIVE" : "DISABLED" })
                }
              >
                {disabled ? m.enable : m.disable}
              </Button>
            </ActionRow>
          )}
        </Section>
      ) : null}
    </article>
  );
}
