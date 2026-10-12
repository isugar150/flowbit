import type { Metadata } from "next";
import { AuthShell } from "@/components/auth/auth-shell";
import { SetupForm } from "@/features/auth/components/setup-form";
import { SetupSteps } from "@/features/auth/components/setup-steps";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.setup.title };

export default function SetupPage() {
  return (
    <AuthShell title={messages.setup.title} description={messages.setup.description}>
      <SetupSteps />
      <SetupForm />
    </AuthShell>
  );
}
