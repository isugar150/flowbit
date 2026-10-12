import { CircleAlertIcon } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";

/** Failure that is not about one field. Rendered above the form and announced to screen readers. */
export function FormAlert({ children }: { children: React.ReactNode }) {
  return (
    <Alert variant="destructive" role="alert">
      <CircleAlertIcon />
      <AlertDescription>{children}</AlertDescription>
    </Alert>
  );
}
