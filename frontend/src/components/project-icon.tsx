import { BoxIcon } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { cn } from "@/lib/utils";

/**
 * A project's icon: the uploaded image, or the default box. `sm` sits in lines of text
 * (sidebar, lists, the path at the top); `lg` heads the project overview. Decorative: the
 * project's name is always next to it.
 */
export function ProjectIcon({
  iconUrl,
  size = "sm",
  className,
}: {
  iconUrl: string | null;
  size?: "sm" | "lg";
  className?: string;
}) {
  const large = size === "lg";
  if (!iconUrl) {
    return large ? (
      <span
        className={cn("flex size-9 items-center justify-center rounded-lg bg-secondary text-muted-foreground", className)}
      >
        <BoxIcon className="size-5" aria-hidden="true" />
      </span>
    ) : (
      <BoxIcon className={cn("size-4 shrink-0 text-muted-foreground", className)} aria-hidden="true" />
    );
  }
  return (
    <Avatar
      aria-hidden="true"
      className={cn(
        "shrink-0",
        large ? "size-9 rounded-lg after:rounded-lg" : "size-4 rounded-[4px] after:rounded-[4px]",
        className,
      )}
    >
      <AvatarImage src={iconUrl} alt="" className={large ? "rounded-lg" : "rounded-[4px]"} />
      <AvatarFallback className={large ? "rounded-lg" : "rounded-[4px]"}>
        <BoxIcon className={large ? "size-5" : "size-3"} aria-hidden="true" />
      </AvatarFallback>
    </Avatar>
  );
}
