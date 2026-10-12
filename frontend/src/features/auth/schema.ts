import { z } from "zod";
import { messages } from "@/messages";

const v = messages.validation;

/** Same rules as the backend's CredentialPolicy. The backend check is the one that counts. */
export const LOGIN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]*$/;

export function normalizeLoginId(raw: string): string {
  return raw.trim().toLowerCase();
}

export const EMAIL_PATTERN = /^[a-z0-9][a-z0-9._+-]*@[a-z0-9-]+(\.[a-z0-9-]+)+$/;

/** What is wrong with a normalized login id: a plain id of 4 to 32 characters, or an e-mail address. */
function loginIdProblem(loginId: string): string | null {
  if (!loginId) return v.loginIdRequired;
  if (loginId.includes("@")) {
    return loginId.length <= 254 && EMAIL_PATTERN.test(loginId) ? null : v.loginIdEmailInvalid;
  }
  if (loginId.length < 4) return v.loginIdTooShort;
  if (loginId.length > 32) return v.loginIdTooLong;
  return LOGIN_ID_PATTERN.test(loginId) ? null : v.loginIdInvalid;
}

export const newLoginId = z
  .string()
  .transform(normalizeLoginId)
  .pipe(
    z.string().superRefine((value, context) => {
      const problem = loginIdProblem(value);
      if (problem) context.addIssue({ code: "custom", message: problem });
    }),
  );

export const newPassword = z.string().min(1, v.passwordRequired).min(10, v.passwordTooShort).max(128, v.passwordTooLong);

export const setupSchema = z
  .object({
    workspaceName: z
      .string()
      .trim()
      .min(1, messages.setup.workspaceNameRequired)
      .max(100, messages.setup.workspaceNameTooLong),
    // Shown before every issue number: FB-12. Typed in any case, stored in capitals.
    workspaceKey: z
      .string()
      .transform((value) => value.trim().toUpperCase())
      .pipe(z.string().regex(/^[A-Z][A-Z0-9]{1,9}$/, messages.setup.workspaceKeyInvalid)),
    loginId: newLoginId,
    password: newPassword,
    passwordConfirm: z.string().min(1, v.passwordConfirmRequired),
  })
  .superRefine((value, context) => {
    if (value.password.toLowerCase() === value.loginId) {
      context.addIssue({ code: "custom", path: ["password"], message: v.passwordSameAsLoginId });
    }
    if (value.passwordConfirm && value.password !== value.passwordConfirm) {
      context.addIssue({ code: "custom", path: ["passwordConfirm"], message: v.passwordMismatch });
    }
  });

/** Sign-in only checks that something was typed; what counts as valid is the server's call. */
export const loginSchema = z.object({
  loginId: z.string().transform(normalizeLoginId).pipe(z.string().min(1, v.loginIdRequired)),
  password: z.string().min(1, v.passwordRequired),
});

export type SetupInput = z.input<typeof setupSchema>;
export type SetupValues = z.output<typeof setupSchema>;
export type LoginInput = z.input<typeof loginSchema>;
export type LoginValues = z.output<typeof loginSchema>;
