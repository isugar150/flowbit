"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import dynamic from "next/dynamic";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { PlusIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import type { UserSummary } from "@/features/members/types";
import { usersAsMembers, type ProjectSummary } from "@/features/projects/types";
import { apiFetch } from "@/lib/api/client";
import { isTyping } from "@/lib/is-typing";
import { messages } from "@/messages";
import type { Label } from "../types";

const m = messages.issues;

const loadDialog = () => import("./new-issue-dialog");
const NewIssueDialog = dynamic(() => loadDialog().then((module) => module.NewIssueDialog), { ssr: false });
const preloadDialog = () => void loadDialog();

const NewIssueContext = createContext<(() => void) | null>(null);

/** The project of the page being looked at (`/project/{id}/…`), if any. */
function projectIdOf(pathname: string): string | null {
  return /^\/project\/([^/]+)/.exec(pathname)?.[1] ?? null;
}

/**
 * "새 티켓" from anywhere in the app: the button in the sidebar and `C` on pages that have no
 * list of their own. On a project's pages the new ticket starts linked to that project, when the
 * person may add tickets to it. A list of tickets opens its own dialog for `C` (it knows the
 * filters in effect) and marks the key as handled, which is checked here a moment later.
 */
export function NewIssueProvider({ projects, children }: { projects: ProjectSummary[]; children: React.ReactNode }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  // The dialog (and the editor in it) is loaded the first time it is asked for.
  const [used, setUsed] = useState(false);
  const show = useCallback(() => {
    setUsed(true);
    setOpen(true);
  }, []);

  const pageProject = projectIdOf(pathname);
  const initialProjectId = useMemo(() => {
    const project = projects.find((item) => item.id === pageProject);
    return project?.myRole && project.myRole !== "VIEWER" ? project.id : null;
  }, [projects, pageProject]);

  const choices = useQuery({
    queryKey: ["new-issue-launcher"],
    enabled: used,
    queryFn: async () => {
      const [users, labels] = await Promise.all([
        apiFetch<UserSummary[]>("/api/v1/users"),
        apiFetch<Label[]>("/api/v1/labels"),
      ]);
      return { members: usersAsMembers(users), labels };
    },
  });

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      // `code` is the physical key, so this also works while a Korean input method is active.
      if (event.code !== "KeyC" || event.ctrlKey || event.metaKey || event.altKey || event.isComposing) return;
      if (isTyping(event.target) || document.querySelector("[role=dialog]")) return;
      // After every other listener has run: a list that opens its own dialog has taken the key.
      setTimeout(() => {
        if (!event.defaultPrevented && !document.querySelector("[role=dialog]")) show();
      }, 0);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [show]);

  return (
    <NewIssueContext value={show}>
      {children}
      {used ? (
        <NewIssueDialog
          open={open}
          onOpenChange={setOpen}
          projects={projects}
          defaultProjectId={null}
          initialProjectId={initialProjectId}
          members={choices.data?.members ?? []}
          milestones={[]}
          labels={choices.data?.labels ?? []}
        />
      ) : null}
    </NewIssueContext>
  );
}

/** Opens the new ticket dialog. `sidebar` is the wide button in the sidebar; `icon` fits a top bar. */
export function NewIssueButton({ variant }: { variant: "sidebar" | "icon" }) {
  const show = useContext(NewIssueContext);
  if (!show) return null;
  if (variant === "icon") {
    return (
      <Button variant="ghost" size="icon-lg" className="size-11" aria-label={m.new} onClick={show} onPointerEnter={preloadDialog} onFocus={preloadDialog}>
        <PlusIcon />
      </Button>
    );
  }
  return (
    <Button variant="outline" className="w-full justify-start" onClick={show} onPointerEnter={preloadDialog} onFocus={preloadDialog}>
      <PlusIcon data-icon="inline-start" />
      {m.new}
      <Kbd className="ml-auto">C</Kbd>
    </Button>
  );
}
