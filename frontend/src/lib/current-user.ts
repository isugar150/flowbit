import { cache } from "react";
import { cookies } from "next/headers";
import { fetchMe, SESSION_COOKIE } from "@/lib/backend";
import type { Me } from "@/features/auth/types";

/**
 * The signed-in user for the current request, fetched once per render however many
 * server components ask. The backend decides; this only relays its answer.
 */
export const getCurrentUser = cache(async (): Promise<Me | null> => {
  const cookieStore = await cookies();
  const session = cookieStore.get(SESSION_COOKIE);
  if (!session) return null;
  return fetchMe(`${SESSION_COOKIE}=${session.value}`);
});
