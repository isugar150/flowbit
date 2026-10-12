import type { Metadata } from "next";
import { AuthShell } from "@/components/auth/auth-shell";
import { LoginForm } from "@/features/auth/components/login-form";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.login.title };

export default function LoginPage() {
  return (
    <AuthShell title={messages.login.title} description={messages.login.description}>
      <LoginForm />
    </AuthShell>
  );
}
