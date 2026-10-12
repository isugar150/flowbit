import type { Metadata } from "next";
import { ServerOffIcon } from "lucide-react";
import Link from "next/link";
import { Wordmark } from "@/components/brand/wordmark";
import { buttonVariants } from "@/components/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.unavailable.title };

/** Shown by proxy.ts in place of any page while the backend cannot be reached. */
export default function UnavailablePage() {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="flex h-14 items-center px-5 sm:px-8">
        <Wordmark />
      </header>
      <main className="flex flex-1 items-center justify-center px-5 pb-16">
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <ServerOffIcon />
            </EmptyMedia>
            <EmptyTitle>
              <h1>{messages.unavailable.title}</h1>
            </EmptyTitle>
            <EmptyDescription>{messages.unavailable.description}</EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            {/* Going home makes proxy.ts ask the backend again and route accordingly. */}
            <Link href="/" prefetch={false} className={buttonVariants({ variant: "outline" })}>
              {messages.unavailable.retry}
            </Link>
          </EmptyContent>
        </Empty>
      </main>
    </div>
  );
}
