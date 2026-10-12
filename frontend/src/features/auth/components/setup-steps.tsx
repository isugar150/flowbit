import { CheckIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

/**
 * Where the installer is in the real install sequence: the server is running (or this page
 * would not load), then the workspace, then the administrator account, then the app opens.
 * `current` is the index of the step being filled in now.
 */
export function SetupSteps({ current: currentStep }: { current: number }) {
  const { steps, stepsLabel, stepDone, stepCurrent } = messages.setup;

  return (
    <ol aria-label={stepsLabel} className="flex items-center gap-1.5 text-[0.8125rem] leading-5">
      {steps.map((step, index) => {
        const done = index < currentStep;
        const current = index === currentStep;
        return (
          <li
            key={step}
            aria-current={current ? "step" : undefined}
            className={cn("flex items-center gap-1.5", index > 0 && "flex-1")}
          >
            {index > 0 ? (
              <span aria-hidden="true" className={cn("h-px min-w-2 flex-1", done || current ? "bg-primary" : "bg-border")} />
            ) : null}
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
            {/* One line each. On a narrow phone only the current step keeps its name on screen. */}
            <span
              className={cn(
                "whitespace-nowrap",
                current ? "font-medium text-foreground" : "text-muted-foreground max-[23.4375rem]:sr-only",
              )}
            >
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
