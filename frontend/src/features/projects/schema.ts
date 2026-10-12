import { z } from "zod";
import { messages } from "@/messages";

const m = messages.projects;

/** Same rules as the backend's Project. The backend check is the one that counts. */

const name = z.string().trim().min(1, m.nameRequired).max(80, m.nameTooLong);
const description = z.string().trim().max(500, m.descriptionTooLong);

export const createProjectSchema = z.object({
  name,
  description,
});

export const projectNameSchema = z.object({ name });
