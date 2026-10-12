import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { initials } from "@/lib/initials";

type PersonLike = {
  loginId: string;
  displayName: string | null;
  avatarUrl: string | null;
};

export function personName(person: PersonLike): string {
  return person.displayName ?? person.loginId;
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
