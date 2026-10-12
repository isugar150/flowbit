import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";

/** The one primary action of a sign-in or setup form. Disabled while a request is in flight so it cannot be sent twice. */
export function SubmitButton({ pending, label, pendingLabel }: { pending: boolean; label: string; pendingLabel: string }) {
  return (
    <Button type="submit" size="lg" className="h-10 w-full max-md:h-11" disabled={pending}>
      {pending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
      {pending ? pendingLabel : label}
    </Button>
  );
}
