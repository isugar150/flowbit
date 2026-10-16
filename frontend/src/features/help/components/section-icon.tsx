import type { LucideIcon } from "lucide-react";

/** A section's icon in a small rounded square: beside its title, on the start cards. */
export function SectionIcon({ icon: Icon, size = "md" }: { icon: LucideIcon; size?: "sm" | "md" }) {
  if (size === "sm") return <Icon className="size-4 shrink-0" aria-hidden="true" />;
  return (
    <span className="flex size-8 shrink-0 items-center justify-center rounded-lg border bg-muted/60 text-muted-foreground">
      <Icon className="size-4" aria-hidden="true" />
    </span>
  );
}
