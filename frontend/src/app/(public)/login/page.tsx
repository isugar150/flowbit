import { Suspense } from "react";
import type { Metadata } from "next";
import { AuthShell } from "@/components/auth/auth-shell";
import { LoginForm } from "@/features/auth/components/login-form";
import { fetchAuthMethods } from "@/lib/backend";
import { safeNextPath } from "@/lib/safe-next-path";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.login.title };

type SearchParams = Promise<{
  error?: string | string[];
  next?: string | string[];
}>;

export default function LoginPage({
  searchParams,
}: {
  searchParams: SearchParams;
}) {
  return (
    <AuthShell
      title={messages.login.title}
      description={messages.login.description}
    >
      {/* The address and the backend are read per request, so this part streams in. */}
      <Suspense fallback={null}>
        <LoginContent searchParams={searchParams} />
      </Suspense>
    </AuthShell>
  );
}

async function LoginContent({ searchParams }: { searchParams: SearchParams }) {
  const [{ error, next }, methods] = await Promise.all([
    searchParams,
    fetchAuthMethods(),
  ]);
  const errors: Record<string, string> = messages.login.ssoErrors;
  // Only the known codes are shown; the address is never echoed onto the page.
  const initialError =
    typeof error === "string" ? (errors[error] ?? null) : null;

  // Single sign-on is a full page navigation; the backend takes the person back to `next` afterwards.
  const start = "/api/v1/auth/sso/start";
  const href =
    typeof next === "string"
      ? `${start}?next=${encodeURIComponent(safeNextPath(next))}`
      : start;
  const sso = methods.sso
    ? { ...methods.sso, href }
    : null;

  return <LoginForm sso={sso} initialError={initialError} />;
}
