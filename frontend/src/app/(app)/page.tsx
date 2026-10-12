import { CircleCheckIcon } from "lucide-react";
import { redirect } from "next/navigation";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { getCurrentUser } from "@/lib/current-user";
import { messages } from "@/messages";

/**
 * Home until teams and issues exist (M3). It says what is true right now and promises nothing
 * it cannot open yet.
 */
export default async function HomePage() {
  const user = await getCurrentUser();
  if (!user) redirect("/login");

  return (
    <div className="flex flex-1 items-center justify-center p-6">
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <CircleCheckIcon />
          </EmptyMedia>
          <EmptyTitle>
            <h1>{messages.home.title}</h1>
          </EmptyTitle>
          <EmptyDescription>{messages.home.description(user.loginId)}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    </div>
  );
}
