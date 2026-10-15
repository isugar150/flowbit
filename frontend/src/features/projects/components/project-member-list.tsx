import { Person } from "@/components/person";
import { Badge } from "@/components/ui/badge";
import { messages } from "@/messages";
import type { ProjectMember } from "../types";

/**
 * The people on the members card of a project overview. Only who they are: no roles, and no way to
 * add or remove anyone here; that is done in the project settings (the user's call, 2026-10-15).
 */
export function ProjectMemberList({
  members,
  currentUserId,
}: {
  members: ProjectMember[];
  /** The reader, marked "나" in the list. */
  currentUserId: string;
}) {
  return (
    <ul className="flex flex-col gap-2 px-1 py-1">
      {members.map((member) => (
        <li key={member.userId} className="flex min-h-8 min-w-0 items-center gap-1.5">
          <Person person={member} />
          {member.userId === currentUserId ? <Badge variant="secondary">{messages.members.you}</Badge> : null}
        </li>
      ))}
    </ul>
  );
}
