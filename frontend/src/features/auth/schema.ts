import { z } from "zod";
import { messages } from "@/messages";

const v = messages.validation;

/** Same rules as the backend's CredentialPolicy. The backend check is the one that counts. */
export const LOGIN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]*$/;

export function normalizeLoginId(raw: string): string {
  return raw.trim().toLowerCase();
}

const newLoginId = z
  .string()
  .transform(normalizeLoginId)
  .pipe(
    z
      .string()
      .min(1, v.loginIdRequired)
      .min(4, v.loginIdTooShort)
      .max(32, v.loginIdTooLong)
      .regex(LOGIN_ID_PATTERN, v.loginIdInvalid),
  );

const newPassword = z.string().min(1, v.passwordRequired).min(10, v.passwordTooShort).max(128, v.passwordTooLong);

export const setupSchema = z
  .object({
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
