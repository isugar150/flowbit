"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { KeyRoundIcon } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { FieldGroup } from "@/components/ui/field";
import { safeNextPath } from "@/lib/safe-next-path";
import { messages } from "@/messages";
import { login } from "../api";
import { formErrorMessage } from "../error-message";
import { loginSchema, type LoginInput, type LoginValues } from "../schema";
import { FormAlert } from "./form-alert";
import { LoginIdField } from "./login-id-field";
import { PasswordField } from "./password-field";
import { SubmitButton } from "./submit-button";

const m = messages.login;

/**
 * @param sso the single sign-on button to offer, when an administrator switched it on; `href` starts it
 * @param initialError why a single sign-on attempt that just came back failed
 */
export function LoginForm({
  sso,
  initialError,
}: {
  sso?: {
    displayName: string;
    href: string;
    buttonStyle: "DEFAULT" | "NAVER_WORKS";
  } | null;
  initialError?: string | null;
}) {
  const router = useRouter();
  const [formError, setFormError] = useState<string | null>(
    initialError ?? null,
  );
  const [signedIn, setSignedIn] = useState(false);
  const form = useForm<LoginInput, unknown, LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { loginId: "", password: "" },
  });
  const mutation = useMutation({ mutationFn: login });
  const { errors } = form.formState;

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      await mutation.mutateAsync(values);
      setSignedIn(true);
      router.replace(
        safeNextPath(new URLSearchParams(window.location.search).get("next")),
      );
      // Drop anything the router cached while signed out.
      router.refresh();
    } catch (error) {
      setFormError(formErrorMessage(error));
      form.resetField("password");
      form.setFocus("password");
    }
  });

  return (
    // method="post": if the browser submits the form itself (its script has not loaded), the
    // password goes in the request body, never in the address.
    <form
      method="post"
      onSubmit={onSubmit}
      noValidate
      className="flex flex-col gap-5"
    >
      {formError ? <FormAlert>{formError}</FormAlert> : null}
      <FieldGroup>
        <LoginIdField
          id="loginId"
          label={m.loginIdLabel}
          registration={form.register("loginId")}
          error={errors.loginId?.message}
          autoFocus
        />
        <PasswordField
          id="password"
          label={messages.fields.password}
          autoComplete="current-password"
          registration={form.register("password")}
          error={errors.password?.message}
        />
      </FieldGroup>
      <SubmitButton
        pending={mutation.isPending || signedIn}
        label={messages.login.submit}
        pendingLabel={messages.login.submitting}
      />
      {sso ? (
        <>
          <div
            className="flex items-center gap-3 text-[0.8125rem] text-muted-foreground"
            aria-hidden="true"
          >
            <span className="h-px flex-1 bg-border" />
            {m.or}
            <span className="h-px flex-1 bg-border" />
          </div>
          {/* A full page navigation: the backend redirects to the identity provider. */}
          {sso.buttonStyle === "NAVER_WORKS" ? (
            // Colours and the icon tile are fixed by the provider's button guide; height and corners follow ours.
            <a
              href={sso.href}
              className="flex h-10 items-center justify-center gap-2 rounded-lg border border-white/15 bg-[linear-gradient(135deg,#14453b,#1b2a26)] text-sm font-semibold tracking-[-0.01em] text-[#f7fffb] shadow-[0_1px_2px_rgba(15,23,42,0.12)] outline-none hover:bg-[linear-gradient(135deg,#17513f,#22332d)] focus-visible:ring-3 focus-visible:ring-ring/50 max-md:h-11 dark:bg-[linear-gradient(135deg,#1b4d40,#232f2b)] dark:text-[#f4f5f7] dark:shadow-none dark:hover:bg-[linear-gradient(135deg,#1f5a49,#2a3a34)]"
            >
              <span className="grid size-[22px] shrink-0 place-items-center rounded-md bg-white/10">
                {/* eslint-disable-next-line @next/next/no-img-element -- a 15px static icon */}
                <img
                  src="/naver-works-app-icon.svg"
                  alt=""
                  className="size-[15px]"
                />
              </span>
              {m.naverWorksSubmit}
            </a>
          ) : (
            <a
              href={sso.href}
              className={buttonVariants({
                variant: "outline",
                className: "h-10 max-md:h-11",
              })}
            >
              <KeyRoundIcon data-icon="inline-start" />
              {m.ssoSubmit(sso.displayName)}
            </a>
          )}
        </>
      ) : null}
    </form>
  );
}
