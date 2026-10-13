"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import { usePathname } from "next/navigation";
import { Button } from "@/components/ui/button";
import { messages } from "@/messages";
import { completeTour } from "../api";
import { EXAMPLE_ISSUES, TOUR_STEPS, type TourStep } from "../steps";
import { TourPractice, type Practice } from "./tour-practice";

const m = messages.tour;

const subscribeToNothing = () => () => {};

const TourContext = createContext<{ start: () => void } | null>(null);

/** Starts the tour again, from the settings. */
export function useTour() {
  const context = useContext(TourContext);
  if (!context) throw new Error("useTour must be used inside TourProvider");
  return context;
}

/**
 * The onboarding tour (docs/features/onboarding-tour.md): shown by itself to someone who has
 * never finished or skipped it, and again whenever `useTour().start()` is called.
 */
export function TourProvider({
  completedAt,
  children,
}: {
  completedAt: string | null;
  children: React.ReactNode;
}) {
  const [step, setStep] = useState<number | null>(
    completedAt === null ? 0 : null,
  );
  // Drawn only once the page's scripts are running, never as part of the HTML the server sends.
  // The tour covers the whole app: if it arrived before (or without) the scripts, its buttons
  // would do nothing and there would be no way past it.
  const inBrowser = useSyncExternalStore(
    subscribeToNothing,
    () => true,
    () => false,
  );
  // An issue opened as a dialog makes everything outside it inert, the tour included: wait for it to close.
  const overDialog = usePathname().startsWith("/issue/");

  const context = useMemo(() => ({ start: () => setStep(0) }), []);
  const close = useCallback(() => {
    setStep(null);
    // Nothing on screen depends on the answer. If it fails the tour comes back on the next visit.
    completeTour().catch(() => {});
  }, []);

  return (
    <TourContext.Provider value={context}>
      {children}
      {inBrowser && step !== null && !overDialog ? (
        <Tour index={step} onMove={setStep} onClose={close} />
      ) : null}
    </TourContext.Provider>
  );
}

type Measured = {
  target: TourStep["target"];
  rect: DOMRect | null;
  width: number;
  height: number;
};

/** Where the visible element named by the step is, kept up to date while the window changes. */
function useTarget(target: TourStep["target"]): Measured | null {
  const [measured, setMeasured] = useState<Measured | null>(null);

  useEffect(() => {
    if (!target) return;
    const measure = () => {
      const element = [
        ...document.querySelectorAll<HTMLElement>(`[data-tour~="${target}"]`),
      ].find((candidate) => candidate.getClientRects().length > 0);
      setMeasured({
        target,
        rect: element?.getBoundingClientRect() ?? null,
        width: window.innerWidth,
        height: window.innerHeight,
      });
    };
    // Reports once as soon as it starts observing, then whenever the window is resized.
    const observer = new ResizeObserver(measure);
    observer.observe(document.documentElement);
    window.addEventListener("scroll", measure, true);
    return () => {
      observer.disconnect();
      window.removeEventListener("scroll", measure, true);
    };
  }, [target]);

  // What was measured for the previous step is not where this one points.
  return measured?.target === target ? measured : null;
}

const GAP = 12;
const CARD_WIDTH = 320;
// The steps with the example issues need room for a line of an issue list.
const PRACTICE_WIDTH = "w-[30rem]";
const HALO = 4;

/** Beside the target when there is room (the sidebar), otherwise under or over it (the top bar). */
function cardPosition({
  rect,
  width,
  height,
}: Measured & { rect: DOMRect }): React.CSSProperties {
  const cardWidth = Math.min(CARD_WIDTH, width - GAP * 2);
  const upperHalf = rect.top + rect.height / 2 < height / 2;
  if (rect.right + GAP + cardWidth + GAP <= width) {
    return {
      width: cardWidth,
      left: rect.right + GAP,
      ...(upperHalf
        ? { top: Math.max(GAP, rect.top) }
        : { bottom: Math.max(GAP, height - rect.bottom) }),
    };
  }
  return {
    width: cardWidth,
    left: Math.min(Math.max(GAP, rect.left), width - cardWidth - GAP),
    ...(upperHalf
      ? { top: rect.bottom + GAP }
      : { bottom: height - rect.top + GAP }),
  };
}

function Tour({
  index,
  onMove,
  onClose,
}: {
  index: number;
  onMove: (index: number) => void;
  onClose: () => void;
}) {
  const step = TOUR_STEPS[index];
  const first = index === 0;
  const last = index === TOUR_STEPS.length - 1;
  const titleId = useId();
  const bodyId = useId();
  const cardRef = useRef<HTMLDivElement>(null);
  const primaryRef = useRef<HTMLButtonElement>(null);
  const measured = useTarget(step.target);
  const waiting = step.target !== undefined && measured === null;
  const rect = measured?.rect ?? null;
  // Kept here, above the steps, so what was tried in one practice step is still there in the next.
  const [practice, setPractice] = useState<Practice>({
    issues: EXAMPLE_ISSUES,
    nextNumber: EXAMPLE_ISSUES.length + 1,
    tried: [],
  });

  const next = () => (last ? onClose() : onMove(index + 1));
  const previous = () => {
    if (!first) onMove(index - 1);
  };

  // Enter goes on, and a screen reader starts from the step that has just appeared.
  useEffect(() => {
    primaryRef.current?.focus();
  }, [index]);

  const onKeyDown = (event: React.KeyboardEvent) => {
    // Nothing behind the tour should react to its keys.
    event.stopPropagation();
    // A menu opened from the card is drawn elsewhere in the document; its keys are its own.
    if (
      !(event.target instanceof Node) ||
      !cardRef.current?.contains(event.target)
    )
      return;
    const typing = event.target instanceof HTMLInputElement;
    if (event.key === "Escape") onClose();
    else if (event.key === "ArrowRight" && !typing) next();
    else if (event.key === "ArrowLeft" && !typing) previous();
    else if (event.key === "Tab") {
      const buttons = [
        ...(cardRef.current?.querySelectorAll<HTMLElement>(
          "button:not(:disabled), input",
        ) ?? []),
      ];
      const edge = event.shiftKey ? buttons[0] : buttons[buttons.length - 1];
      if (document.activeElement === edge) {
        event.preventDefault();
        (event.shiftKey ? buttons[buttons.length - 1] : buttons[0])?.focus();
      }
    }
  };

  return (
    // Covers the app so that nothing behind can be pressed; pressing it keeps the focus in the card.
    // Under the menus (z-50), which the example issues open over it.
    <div
      className={`fixed inset-0 z-40 ${rect ? "" : "bg-black/45"}`}
      onPointerDown={(event) => {
        if (event.target === event.currentTarget) event.preventDefault();
      }}
    >
      {rect ? (
        // The shadow is what dims the rest of the screen, leaving the target as it is.
        <div
          aria-hidden="true"
          className="pointer-events-none fixed rounded-lg shadow-[0_0_0_100vmax_rgb(0_0_0/0.45)] ring-2 ring-primary"
          style={{
            top: rect.top - HALO,
            left: rect.left - HALO,
            width: rect.width + HALO * 2,
            height: rect.height + HALO * 2,
          }}
        />
      ) : null}
      {
        // Stays in the document while the next target is being found (one frame), unseen: taking
        // it out would drop the focus, and a key pressed in that moment would go nowhere.
        <div
          ref={cardRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          aria-describedby={bodyId}
          onKeyDown={onKeyDown}
          className={`fixed flex flex-col gap-4 rounded-xl border bg-popover p-4 text-popover-foreground shadow-lg ${waiting ? "opacity-0" : ""} ${
            rect
              ? ""
              : `inset-0 m-auto h-fit max-w-[calc(100vw-1.5rem)] ${step.practice ? PRACTICE_WIDTH : "w-80"}`
          }`}
          style={
            rect && measured ? cardPosition({ ...measured, rect }) : undefined
          }
        >
          <div className="flex flex-col gap-1.5">
            <h2
              id={titleId}
              className="text-[0.9375rem] leading-[1.375rem] font-semibold"
            >
              {step.title}
            </h2>
            <p
              id={bodyId}
              className="text-[0.8125rem] leading-5 text-muted-foreground"
            >
              {step.body}
            </p>
          </div>
          {step.practice ? (
            <TourPractice
              task={step.practice}
              practice={practice}
              onChange={setPractice}
            />
          ) : null}
          <div className="flex items-center gap-2">
            <span
              className="text-xs text-muted-foreground tabular-nums"
              aria-hidden="true"
            >
              {m.progress(index + 1, TOUR_STEPS.length)}
            </span>
            <div className="ml-auto flex items-center gap-1">
              {last ? null : (
                <Button
                  variant="ghost"
                  size="sm"
                  className="max-md:h-11"
                  onClick={onClose}
                >
                  {m.skip}
                </Button>
              )}
              {first ? null : (
                <Button
                  variant="outline"
                  size="sm"
                  className="max-md:h-11"
                  onClick={previous}
                >
                  {m.previous}
                </Button>
              )}
              <Button
                ref={primaryRef}
                size="sm"
                className="max-md:h-11"
                onClick={next}
              >
                {first ? m.start : last ? m.finish : m.next}
              </Button>
            </div>
          </div>
        </div>
      }
    </div>
  );
}
