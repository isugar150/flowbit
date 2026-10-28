import { ModKey } from "@/components/mod-key";
import { Kbd, KbdGroup } from "@/components/ui/kbd";
import { shortcutOf } from "@/lib/shortcuts";
import { cn } from "@/lib/utils";

/**
 * A key combination as key caps: `Mod+Enter` is Ctrl then Enter (⌘ on a Mac), `G M` is G and then M. The one place that
 * draws shortcuts, so they look the same everywhere (lib/shortcuts.ts).
 */
export function KeyCaps({ combo, className }: { combo: string; className?: string }) {
  const steps = combo.split(" ");
  return (
    <KbdGroup className={className}>
      {steps.map((step, stepIndex) => (
        <span key={stepIndex} className="inline-flex items-center gap-1">
          {stepIndex > 0 ? <span className="text-xs text-muted-foreground">→</span> : null}
          <KbdGroup>
            {step.split("+").map((key, index) => (
              <Kbd key={index}>{key === "Mod" ? <ModKey /> : key === "Enter" ? "↵" : key}</Kbd>
            ))}
          </KbdGroup>
        </span>
      ))}
    </KbdGroup>
  );
}

/** The key caps of a shortcut of the registry, for beside a button or a menu line. */
export function ShortcutKeys({ id, className }: { id: string; className?: string }) {
  return <KeyCaps combo={shortcutOf(id).keys[0]} className={cn("max-md:hidden", className)} />;
}
