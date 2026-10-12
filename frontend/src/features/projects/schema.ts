import { z } from "zod";
import { messages } from "@/messages";

const m = messages.projects;

/** Same rules as the backend's Project. The backend check is the one that counts. */
export const PROJECT_KEY_PATTERN = /^[A-Z][A-Z0-9]{1,9}$/;

const name = z.string().trim().min(1, m.nameRequired).max(80, m.nameTooLong);
const description = z.string().trim().max(500, m.descriptionTooLong);

export const createProjectSchema = z.object({
  name,
  key: z
    .string()
    .transform((value) => value.trim().toUpperCase())
    .pipe(z.string().regex(PROJECT_KEY_PATTERN, m.keyInvalid)),
  description,
});

export const projectNameSchema = z.object({ name });

/**
 * A key to offer while the name is being typed: the initials of several words ("Payment Rework"
 * gives "PR"), or the first letters of a single word ("Billing" gives "BILL"). Names without
 * Latin letters, such as Korean ones, give nothing and the person types the key.
 */
export function suggestProjectKey(projectName: string): string {
  const words = projectName
    .toUpperCase()
    .split(/[^A-Z0-9]+/)
    .filter(Boolean);
  const candidate = words.length > 1 ? words.map((word) => word[0]).join("") : (words[0] ?? "").slice(0, 4);
  const key = candidate.replace(/^[0-9]+/, "").slice(0, 10);
  return key.length >= 2 ? key : "";
}
