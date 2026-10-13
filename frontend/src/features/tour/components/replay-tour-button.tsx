"use client";

import { Button } from "@/components/ui/button";
import { messages } from "@/messages";
import { useTour } from "./onboarding-tour";

/** Shows the onboarding tour again. The time it was first seen stays as it is. */
export function ReplayTourButton() {
  const { start } = useTour();
  return (
    <div>
      <Button variant="outline" className="max-md:h-11" onClick={start}>
        {messages.tour.replay.button}
      </Button>
    </div>
  );
}
