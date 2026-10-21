"use client";

import { createContext, useContext, useEffect, useState } from "react";
import { apiFetch } from "@/lib/api/client";
import { DEFAULT_UPLOAD_LIMIT, formatLimit } from "./upload-limit";

const UploadLimitContext = createContext(DEFAULT_UPLOAD_LIMIT);

/**
 * Tells the screens under it how large a file the server takes (FLOWBIT_MAX_UPLOAD_SIZE, D-174), so that they check a
 * file against the real limit and say what it is. The signed-in pages get `bytes` from the server render; the setup
 * page has no server render of it (nobody is signed in), so without `bytes` the limit is asked for once the page is open.
 */
export function UploadLimitProvider({ bytes, children }: { bytes?: number; children: React.ReactNode }) {
  const [asked, setAsked] = useState<number | null>(null);
  useEffect(() => {
    if (bytes !== undefined) return;
    let stopped = false;
    apiFetch<{ maxUploadBytes: number }>("/api/v1/upload-limit")
      .then((limit) => {
        if (!stopped) setAsked(limit.maxUploadBytes);
      })
      // The default stays; the server still refuses a file that is too large and says by how much.
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [bytes]);
  return <UploadLimitContext value={bytes ?? asked ?? DEFAULT_UPLOAD_LIMIT}>{children}</UploadLimitContext>;
}

/** The largest file the server takes, in bytes and as a person reads it ("10MB"). */
export function useUploadLimit(): { bytes: number; label: string } {
  const bytes = useContext(UploadLimitContext);
  return { bytes, label: formatLimit(bytes) };
}
