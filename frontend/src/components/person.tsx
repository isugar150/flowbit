import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { initial, initials } from "@/lib/initials";

export type PersonLike = {
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
};

export function personName(person: PersonLike): string {
  return person.displayName ?? person.loginId;
}

/** The small avatar that stands for a person in dense places: list rows, menus, chips. */
export function PersonAvatar({ person }: { person: PersonLike }) {
  return (
    <Avatar size="sm" className="size-4.5">
      {person.avatarUrl ? <AvatarImage src={person.avatarUrl} alt="" /> : null}
      <AvatarFallback className="text-[0.625rem]">{initial(personName(person))}</AvatarFallback>
    </Avatar>
  );
}

/** The small avatar on its own, with the name as a tooltip and for screen readers. */
export function PersonMark({ person }: { person: PersonLike }) {
  const name = personName(person);
  return (
    <span title={name} className="flex shrink-0">
      <PersonAvatar person={person} />
      <span className="sr-only">{name}</span>
    </span>
  );
}

/** Avatar, name and login id of one person, as shown in member lists. */
export function Person({ person, children }: { person: PersonLike; children?: React.ReactNode }) {
  const name = personName(person);
  return (
    <div className="flex min-w-0 items-center gap-3">
      <Avatar>
        {person.avatarUrl ? <AvatarImage src={person.avatarUrl} alt="" /> : null}
        <AvatarFallback>{initials(name)}</AvatarFallback>
      </Avatar>
      <div className="flex min-w-0 flex-col">
        <span className="flex min-w-0 items-center gap-2">
          <span className="truncate font-medium">{name}</span>
          {children}
        </span>
        {person.displayName ? (
          <span className="truncate text-[0.8125rem] leading-5 text-muted-foreground">{person.loginId}</span>
        ) : null}
      </div>
    </div>
  );
}
