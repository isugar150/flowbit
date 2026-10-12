"use client";

import { ServerOffIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { messages } from "@/messages";

const m = messages.unavailable;

/** A page inside the app could not load its data, most often because the backend did not answer. */
export default function AppError({ reset }: { error: Error; reset: () => void }) {
  return (
    <div className="flex min-h-dvh flex-1 items-center justify-center p-6">
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <ServerOffIcon />
          </EmptyMedia>
          <EmptyTitle>
            <h1>{m.title}</h1>
          </EmptyTitle>
          <EmptyDescription>{m.description}</EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button variant="outline" onClick={reset}>
            {m.retry}
          </Button>
        </EmptyContent>
      </Empty>
    </div>
  );
}
