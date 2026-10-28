"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BookmarkIcon, GlobeIcon, SettingsIcon, SaveIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiFetch } from "@/lib/api/client";
import { apiErrorMessage } from "@/lib/api/error-message";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";
import { chipClass } from "./chip-select";
import { useReplaceListQuery } from "./issue-views";

const m = messages.issues.savedViews;

/** The lists a view can belong to (the server takes these paths only). */
export const VIEW_PATHS = ["/issues", "/my-issues", "/my-issues/created"];

/** What of a list's address a saved view keeps: its filters, order, grouping and the way of showing; not the page or the deleted toggle. */
const KEPT_KEYS = ["project", "status", "priority", "assignee", "due", "label", "q", "sort", "group", "completed", "view", "cycle"];

export type SavedView = {
  id: string;
  name: string;
  path: string;
  query: string;
  shared: boolean;
  owner: { id: string; loginId: string; displayName: string | null };
  mine: boolean;
  /** The maker, or an administrator. */
  canDelete: boolean;
};

const KEY = ["saved-views"];

/** The query of the list as it is now, cut down to what a view keeps. */
export function viewQueryOf(searchParams: URLSearchParams): string {
  const kept = new URLSearchParams();
  for (const key of KEPT_KEYS) {
    const value = searchParams.get(key);
    if (value) kept.set(key, value);
  }
  return kept.toString();
}

function useViews(enabled: boolean) {
  return useQuery({ queryKey: KEY, queryFn: () => apiFetch<SavedView[]>("/api/v1/views"), enabled, staleTime: 30_000 });
}

/** Names the current list and keeps it as a view, for the person or for everyone. */
function SaveDialog({ open, onOpenChange, path, query }: { open: boolean; onOpenChange: (open: boolean) => void; path: string; query: string }) {
  const client = useQueryClient();
  const [name, setName] = useState("");
  const [shared, setShared] = useState(false);
  const save = useMutation({
    mutationFn: () => apiFetch<SavedView>("/api/v1/views", { method: "POST", body: { name: name.trim(), path, query, shared } }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: KEY });
      setName("");
      setShared(false);
      onOpenChange(false);
    },
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (name.trim()) save.mutate();
          }}
        >
          <DialogHeader>
            <DialogTitle>{m.saveTitle}</DialogTitle>
            <DialogDescription>{m.saveDescription}</DialogDescription>
          </DialogHeader>
          {save.isError ? <FormAlert>{apiErrorMessage(save.error)}</FormAlert> : null}
          <label className="flex flex-col gap-1.5 text-[0.8125rem] leading-5">
            {m.nameLabel}
            <Input value={name} maxLength={40} autoFocus onChange={(event) => setName(event.target.value)} />
          </label>
          <label className="flex items-start gap-2 text-[0.8125rem] leading-5">
            <input type="checkbox" checked={shared} onChange={(event) => setShared(event.target.checked)} className="mt-0.5 size-4" />
            <span>
              {m.shareLabel}
              <span className="block text-muted-foreground">{m.shareHint}</span>
            </span>
          </label>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              {m.cancel}
            </Button>
            <Button type="submit" disabled={!name.trim() || save.isPending}>
              {m.save}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** The views, to share, bring up to date with the list as it is now, or delete. */
function ManageDialog({ open, onOpenChange, path, query }: { open: boolean; onOpenChange: (open: boolean) => void; path: string; query: string }) {
  const client = useQueryClient();
  const views = useViews(open);
  const [confirming, setConfirming] = useState<string | null>(null);
  const done = () => void client.invalidateQueries({ queryKey: KEY });
  const patch = useMutation({
    mutationFn: ({ id, changes }: { id: string; changes: Partial<Pick<SavedView, "name" | "query" | "shared">> }) =>
      apiFetch<SavedView>(`/api/v1/views/${id}`, { method: "PATCH", body: changes }),
    onSuccess: done,
  });
  const remove = useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/api/v1/views/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      setConfirming(null);
      done();
    },
  });
  const rows = (views.data ?? []).filter((view) => view.canDelete);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{m.manageTitle}</DialogTitle>
          <DialogDescription>{m.manageDescription}</DialogDescription>
        </DialogHeader>
        {patch.isError || remove.isError ? <FormAlert>{apiErrorMessage(patch.error ?? remove.error)}</FormAlert> : null}
        {rows.length === 0 ? <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.empty}</p> : null}
        <ul className="flex max-h-80 flex-col gap-2 overflow-y-auto">
          {rows.map((view) => (
            <li key={view.id} className="flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-[0.8125rem] leading-5">
              <span className="min-w-0 flex-1 truncate font-medium">
                {view.name}
                {!view.mine ? <span className="font-normal text-muted-foreground"> · {m.madeBy(view.owner.displayName ?? view.owner.loginId)}</span> : null}
              </span>
              {view.mine ? (
                <>
                  <Button variant="ghost" size="sm" onClick={() => patch.mutate({ id: view.id, changes: { shared: !view.shared } })}>
                    {view.shared ? m.unshare : m.share}
                  </Button>
                  {view.path === path ? (
                    <Button variant="ghost" size="sm" onClick={() => patch.mutate({ id: view.id, changes: { query } })}>
                      {m.overwrite}
                    </Button>
                  ) : null}
                </>
              ) : null}
              {confirming === view.id ? (
                <Button variant="destructive" size="sm" onClick={() => remove.mutate(view.id)}>
                  {m.confirmDelete}
                </Button>
              ) : (
                <Button variant="ghost" size="sm" onClick={() => setConfirming(view.id)}>
                  {m.delete}
                </Button>
              )}
            </li>
          ))}
        </ul>
      </DialogContent>
    </Dialog>
  );
}

/**
 * "뷰": the views a person saved and the ones shared with everyone (docs/features/saved-views.md). Choosing one opens its list
 * with its filters, order, grouping and way of showing; "저장" keeps the list as it is now under a name.
 */
export function SavedViewsMenu({
  path,
  searchParams,
  onApplied,
}: {
  /** The list being shown; the menu is only on the lists a view can belong to. */
  path: string;
  searchParams: URLSearchParams;
  /** Told the query a view put in the address, so that the search box can show its text. */
  onApplied: (params: URLSearchParams) => void;
}) {
  const router = useRouter();
  const replaceListQuery = useReplaceListQuery();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [managing, setManaging] = useState(false);
  const views = useViews(open);
  const query = viewQueryOf(searchParams);
  const mine = (views.data ?? []).filter((view) => view.mine);
  const shared = (views.data ?? []).filter((view) => !view.mine);

  const apply = (view: SavedView) => {
    const params = new URLSearchParams(view.query);
    if (view.path === path) {
      replaceListQuery(path, params);
      onApplied(params);
    } else {
      router.push(view.query ? `${view.path}?${view.query}` : view.path);
    }
  };

  const item = (view: SavedView) => (
    <DropdownMenuItem key={view.id} onClick={() => apply(view)} className="max-md:py-2">
      {view.shared ? <GlobeIcon className="size-4 text-muted-foreground" aria-hidden /> : <BookmarkIcon className="size-4 text-muted-foreground" aria-hidden />}
      <span className="truncate">{view.name}</span>
    </DropdownMenuItem>
  );

  return (
    <>
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger
          render={<button type="button" aria-label={m.button} className={cn(chipClass, "border-dashed text-muted-foreground hover:text-foreground aria-expanded:text-foreground")} />}
        >
          <BookmarkIcon className="size-3.5" aria-hidden="true" />
          <span>{m.button}</span>
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-56">
          <DropdownMenuGroup>
            {views.isLoading ? <p className="px-2 py-1.5 text-xs text-muted-foreground">{m.loading}</p> : null}
            {mine.length > 0 ? <DropdownMenuLabel>{m.mine}</DropdownMenuLabel> : null}
            {mine.map(item)}
            {shared.length > 0 ? <DropdownMenuLabel>{m.shared}</DropdownMenuLabel> : null}
            {shared.map(item)}
            {!views.isLoading && (views.data ?? []).length === 0 ? <p className="px-2 py-1.5 text-xs text-muted-foreground">{m.empty}</p> : null}
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => setSaving(true)} className="max-md:py-2">
            <SaveIcon className="size-4 text-muted-foreground" aria-hidden />
            {m.saveCurrent}
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => setManaging(true)} className="max-md:py-2">
            <SettingsIcon className="size-4 text-muted-foreground" aria-hidden />
            {m.manage}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <SaveDialog open={saving} onOpenChange={setSaving} path={path} query={query} />
      <ManageDialog open={managing} onOpenChange={setManaging} path={path} query={query} />
    </>
  );
}
