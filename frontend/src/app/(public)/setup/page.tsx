import type { Metadata } from "next";
import { AuthShell } from "@/components/auth/auth-shell";
import { SetupForm } from "@/features/auth/components/setup-form";
import { UploadLimitProvider } from "@/features/settings/upload-limit-provider";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.setup.title };

export default function SetupPage() {
  return (
    <AuthShell title={messages.setup.title} description={messages.setup.description}>
      {/* Nobody is signed in here: the logo is checked against the server's limit once the page asks for it. */}
      <UploadLimitProvider>
        <SetupForm />
      </UploadLimitProvider>
    </AuthShell>
  );
}
