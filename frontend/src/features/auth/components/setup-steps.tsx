import { CheckIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const CURRENT_STEP = 1;

/**
 * Where the installer is in the real install sequence: the server is running (or this page
 * would not load), the administrator account is being created now, then the app opens.
 */
export function SetupSteps() {
  const { steps, stepsLabel, stepDone, stepCurrent } = messages.setup;

  return (
    <ol aria-label={stepsLabel} className="flex items-center gap-2 text-[0.8125rem] leading-5">
      {steps.map((step, index) => {
        const done = index < CURRENT_STEP;
        const current = index === CURRENT_STEP;
        return (
          <li
            key={step}
            aria-current={current ? "step" : undefined}
            className={cn("flex items-center gap-2", index > 0 && "flex-1")}
          >
            {index > 0 ? <span aria-hidden="true" className={cn("h-px flex-1", done || current ? "bg-primary" : "bg-border")} /> : null}
            <span
              aria-hidden="true"
              className={cn(
                "flex size-4 shrink-0 items-center justify-center rounded-full border",
                done && "border-primary bg-primary text-primary-foreground",
                current && "border-primary",
                !done && !current && "border-input",
              )}
            >
              {done ? <CheckIcon className="size-3" strokeWidth={3} /> : null}
              {current ? <span className="size-1.5 rounded-full bg-primary" /> : null}
            </span>
            <span className={cn(current ? "font-medium text-foreground" : "text-muted-foreground")}>
              {step}
              {done ? <span className="sr-only"> ({stepDone})</span> : null}
              {current ? <span className="sr-only"> ({stepCurrent})</span> : null}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
