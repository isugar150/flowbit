"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { useTheme } from "next-themes";
import { CornerDownLeftIcon, SearchIcon, Undo2Icon, XIcon } from "lucide-react";
import { KeyCaps } from "@/components/shortcut-keys";
import { Button } from "@/components/ui/button";
import { Kbd, KbdGroup } from "@/components/ui/kbd";
import { useNewIssue } from "@/features/issues/components/new-issue-launcher";
import { useListAddress, useReplaceListQuery } from "@/features/issues/components/issue-views";
import { VIEW_PATHS } from "@/features/issues/components/saved-views-menu";
import { currentNotice, dismissNotice, hasUndo, subscribeUndo, undoLast } from "@/features/issues/undo";
import type { ProjectSummary } from "@/features/projects/types";
import { useOpenSearch } from "@/features/search/components/search-dialog";
import { isTyping } from "@/lib/is-typing";
import { GO_TARGETS, SHORTCUTS, SHORTCUT_GROUPS, shortcutOf } from "@/lib/shortcuts";
import { cn } from "@/lib/utils";
import { messages } from "@/messages";

const m = messages.command;

/** How long after `G` the letter that says where to go is waited for. */
const GO_WINDOW_MS = 1500;
/** How long the notice about a change stays on screen. */
const NOTICE_MS = 7000;

type Command = { id: string; group: string; label: string; keywords?: string; shortcut?: string; run: () => void };

const optionClass = (active: boolean) =>
  cn("flex min-h-9 cursor-pointer items-center gap-2 rounded-lg px-2.5 py-1.5 text-[0.8125rem] leading-5", active && "bg-accent text-accent-foreground");

/** The command palette: every action and place of the app, found by its name. */
function Palette({ open, onOpenChange, commands }: { open: boolean; onOpenChange: (open: boolean) => void; commands: Command[] }) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const listId = useId();
  const needle = query.trim().toLowerCase();
  const shown = useMemo(
    () => commands.filter((command) => !needle || `${command.label} ${command.keywords ?? ""} ${command.group}`.toLowerCase().includes(needle)),
    [commands, needle],
  );
  const current = shown.length > 0 ? Math.min(active, shown.length - 1) : -1;

  const run = (command: Command) => {
    onOpenChange(false);
    // After the palette has closed, so that what it opens (the search, the new-ticket dialog) is not closed with it.
    setTimeout(command.run, 0);
  };

  return (
    <DialogPrimitive.Root
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next);
        if (!next) {
          setQuery("");
          setActive(0);
        }
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/20" />
        <DialogPrimitive.Popup
          aria-label={m.title}
          className="fixed top-[12vh] left-1/2 z-50 flex max-h-[min(34rem,76vh)] w-[min(36rem,calc(100%-1.5rem))] -translate-x-1/2 flex-col overflow-hidden rounded-xl bg-popover text-sm text-popover-foreground shadow-2xl ring-1 ring-foreground/10 outline-none max-sm:top-3 max-sm:max-h-[calc(100dvh-1.5rem)]"
        >
          <div className="flex items-center gap-2 border-b px-4">
            <SearchIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <input
              autoFocus
              role="combobox"
              aria-expanded={shown.length > 0}
              aria-controls={listId}
              aria-activedescendant={current >= 0 ? `${listId}-${current}` : undefined}
              aria-autocomplete="list"
              aria-label={m.inputLabel}
              placeholder={m.placeholder}
              autoComplete="off"
              spellCheck={false}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setActive(0);
              }}
              onKeyDown={(event) => {
                if (event.nativeEvent.isComposing) return;
                if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                  event.preventDefault();
                  if (shown.length === 0) return;
                  const step = event.key === "ArrowDown" ? 1 : -1;
                  setActive((current < 0 ? 0 : current + step + shown.length) % shown.length);
                } else if (event.key === "Enter") {
                  event.preventDefault();
                  if (current >= 0) run(shown[current]);
                }
              }}
              className="h-12 min-w-0 flex-1 bg-transparent text-[0.9375rem] outline-none placeholder:text-muted-foreground max-sm:h-14"
            />
            <Kbd className="max-sm:hidden">Esc</Kbd>
          </div>
          <div id={listId} role="listbox" aria-label={m.listLabel} className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-2">
            {shown.length === 0 ? <p className="px-2.5 py-6 text-center text-[0.8125rem] leading-5 text-muted-foreground">{m.noResults(query.trim())}</p> : null}
            {shown.map((command, index) => (
              <div key={command.id}>
                {index === 0 || shown[index - 1].group !== command.group ? (
                  <p className={cn("px-2.5 pt-1 pb-1.5 text-xs font-medium text-muted-foreground", index > 0 && "mt-2")}>{command.group}</p>
                ) : null}
                <div
                  id={`${listId}-${index}`}
                  role="option"
                  aria-selected={index === current}
                  className={optionClass(index === current)}
                  onMouseMove={() => setActive(index)}
                  onClick={() => run(command)}
                >
                  <span className="min-w-0 flex-1 truncate">{command.label}</span>
                  {command.shortcut ? <KeyCaps combo={shortcutOf(command.shortcut).keys[0]} className="max-md:hidden" /> : null}
                </div>
              </div>
            ))}
          </div>
          <div className="flex items-center gap-3 border-t px-4 py-2 text-xs text-muted-foreground max-sm:hidden">
            <span className="flex items-center gap-1">
              <KbdGroup>
                <Kbd>↑</Kbd>
                <Kbd>↓</Kbd>
              </KbdGroup>
              {m.move}
            </span>
            <span className="flex items-center gap-1">
              <Kbd>
                <CornerDownLeftIcon aria-hidden="true" />
              </Kbd>
              {m.run}
            </span>
          </div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

/** Every shortcut, in the groups of lib/shortcuts.ts (`?`). */
function ShortcutSheet({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/20" />
        <DialogPrimitive.Popup
          aria-label={m.sheetTitle}
          className="fixed top-[8vh] left-1/2 z-50 flex max-h-[84vh] w-[min(40rem,calc(100%-1.5rem))] -translate-x-1/2 flex-col overflow-hidden rounded-xl bg-popover text-sm text-popover-foreground shadow-2xl ring-1 ring-foreground/10 outline-none"
        >
          <div className="flex items-center justify-between border-b px-4 py-3">
            <DialogPrimitive.Title className="text-[0.9375rem] font-semibold">{m.sheetTitle}</DialogPrimitive.Title>
            <DialogPrimitive.Close render={<Button variant="ghost" size="icon-sm" aria-label={m.close} />}>
              <XIcon />
            </DialogPrimitive.Close>
          </div>
          <div className="flex flex-col gap-4 overflow-y-auto p-4">
            <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.sheetNote}</p>
            {SHORTCUT_GROUPS.map((group) => (
              <section key={group.id} className="flex flex-col gap-1">
                <h3 className="text-xs font-medium text-muted-foreground">{group.title}</h3>
                <ul>
                  {SHORTCUTS.filter((shortcut) => shortcut.group === group.id).map((shortcut) => (
                    <li key={shortcut.id} className="flex items-center gap-3 border-b py-1.5 text-[0.8125rem] leading-5 last:border-b-0">
                      <span className="min-w-0 flex-1">{shortcut.label}</span>
                      <span className="flex shrink-0 items-center gap-2">
                        {shortcut.keys.map((combo) => (
                          <KeyCaps key={combo} combo={combo} />
                        ))}
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

/** The notice about the last change, with the way to take it back. */
function UndoNotice() {
  const router = useRouter();
  const notice = useSyncExternalStore(subscribeUndo, currentNotice, () => null);
  const [failed, setFailed] = useState<string | null>(null);

  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(dismissNotice, NOTICE_MS);
    return () => clearTimeout(timer);
  }, [notice]);

  if (!notice) return null;
  const undo = () => {
    setFailed(null);
    undoLast()
      .then(() => router.refresh())
      .catch(() => setFailed(m.undoFailed));
  };
  return (
    <div
      role="status"
      className="fixed bottom-4 left-1/2 z-[60] flex max-w-[calc(100%-1.5rem)] -translate-x-1/2 items-center gap-3 rounded-lg bg-foreground px-4 py-2.5 text-[0.8125rem] leading-5 text-background shadow-lg"
    >
      <span className="min-w-0 truncate">{failed ?? (notice.undoable ? notice.text : m.undone(notice.text))}</span>
      {notice.undoable && !failed ? (
        <button type="button" onClick={undo} className="flex shrink-0 items-center gap-1 rounded-sm font-medium underline-offset-2 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-background/60">
          <Undo2Icon className="size-3.5" aria-hidden="true" />
          {m.undo}
          <KeyCaps combo={shortcutOf("undo").keys[0]} className="max-md:hidden [&_kbd]:bg-background/20 [&_kbd]:text-background" />
        </button>
      ) : null}
      <button type="button" aria-label={m.close} onClick={dismissNotice} className="shrink-0 rounded-sm opacity-70 outline-none hover:opacity-100 focus-visible:ring-2 focus-visible:ring-background/60">
        <XIcon className="size-3.5" />
      </button>
    </div>
  );
}

/**
 * The keyboard of the app (docs/features/command-palette.md, D-233): `Mod+K` opens the command palette, `?` the sheet of all
 * shortcuts, `G` and a letter goes to a place, `Mod+Z` takes the last change to an issue back. `C` and `/` belong to the new-ticket
 * and the search providers, which listen for them themselves. Nothing here fires while a text is being typed or a dialog is open,
 * except `Mod+K`, which works from anywhere.
 */
export function CommandCenter({ projects, isAdmin }: { projects: ProjectSummary[]; isAdmin: boolean }) {
  const router = useRouter();
  const { setTheme } = useTheme();
  const openSearch = useOpenSearch();
  const newIssue = useNewIssue();
  const { pathname, searchParams } = useListAddress();
  const replaceListQuery = useReplaceListQuery();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);
  const goAt = useRef(0);

  const undo = useCallback(() => {
    if (!hasUndo()) return;
    undoLast()
      .then(() => router.refresh())
      .catch(() => undefined);
  }, [router]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing) return;
      const plain = !event.ctrlKey && !event.metaKey && !event.altKey;
      if (event.code === "KeyK" && (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey) {
        event.preventDefault();
        setSheetOpen(false);
        setPaletteOpen((open) => !open);
        return;
      }
      if (isTyping(event.target) || document.querySelector("[role=dialog]")) return;
      if (event.code === "KeyZ" && (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey) {
        if (!hasUndo()) return;
        event.preventDefault();
        undo();
        return;
      }
      if (event.key === "?" && plain) {
        event.preventDefault();
        setSheetOpen(true);
        return;
      }
      if (!plain || event.shiftKey) return;
      const waiting = Date.now() - goAt.current < GO_WINDOW_MS;
      if (waiting) {
        goAt.current = 0;
        const target = GO_TARGETS.find((item) => item.code === event.code);
        if (target) {
          event.preventDefault();
          router.push(target.href);
        }
        return;
      }
      if (event.code === "KeyG") goAt.current = Date.now();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [router, undo]);

  const commands = useMemo<Command[]>(() => {
    const go = (id: string, label: string, href: string, keywords = ""): Command => ({
      id,
      group: m.groups.go,
      label: m.goTo(label),
      keywords,
      shortcut: GO_TARGETS.find((target) => target.href === href)?.shortcut,
      run: () => router.push(href),
    });
    const view = (mode: "list" | "board" | "timeline" | "calendar"): Command => ({
      id: `view-${mode}`,
      group: m.groups.view,
      label: m.showAs(messages.issues.views[mode]),
      keywords: mode,
      run: () => {
        const params = new URLSearchParams(searchParams.toString());
        if (mode === "list") params.delete("view");
        else params.set("view", mode);
        replaceListQuery(pathname, params);
      },
    });
    return [
      { id: "new-issue", group: m.groups.do, label: m.newIssue, keywords: "새 티켓 만들기 new issue", shortcut: "new-issue", run: () => newIssue?.() },
      { id: "search", group: m.groups.do, label: m.search, keywords: "검색 찾기 search", shortcut: "search", run: () => openSearch?.() },
      { id: "undo", group: m.groups.do, label: m.undoLast, keywords: "실행 취소 되돌리기 undo", shortcut: "undo", run: undo },
      { id: "help-keys", group: m.groups.do, label: m.showShortcuts, keywords: "단축키 shortcuts keyboard", shortcut: "help", run: () => setSheetOpen(true) },
      { id: "theme-light", group: m.groups.do, label: m.theme(messages.settings.appearance.light), keywords: "테마 밝게 light", run: () => setTheme("light") },
      { id: "theme-dark", group: m.groups.do, label: m.theme(messages.settings.appearance.dark), keywords: "테마 어둡게 dark", run: () => setTheme("dark") },
      { id: "theme-system", group: m.groups.do, label: m.theme(messages.settings.appearance.system), keywords: "테마 기기 설정 system", run: () => setTheme("system") },
      ...(VIEW_PATHS.includes(pathname) ? (["list", "board", "timeline", "calendar"] as const).map(view) : []),
      go("go-my", messages.myIssues.title, "/my-issues", "내 티켓 assigned"),
      go("go-created", messages.myIssues.created, "/my-issues/created", "요청한 티켓 created"),
      go("go-all", messages.issues.all, "/issues", "전체 티켓 all issues"),
      go("go-triage", messages.triage.title, "/triage", "트리아지 분류 triage"),
      go("go-projects", messages.projects.all, "/", "프로젝트 projects"),
      go("go-settings", messages.settings.title, "/settings", "설정 비밀번호 알림 settings"),
      go("go-help", messages.userMenu.help, "/help", "도움말 사용법 help"),
      ...(isAdmin ? [go("go-admin", messages.admin.title, "/admin", "관리자 설정 admin audit 감사 로그 상태")] : []),
      ...projects
        .filter((project) => project.myRole !== null)
        .map((project) => ({
          id: `project-${project.id}`,
          group: m.groups.projects,
          label: project.name,
          keywords: "프로젝트 project",
          run: () => router.push(`/project/${project.id}`),
        })),
    ];
  }, [router, newIssue, openSearch, undo, setTheme, pathname, searchParams, replaceListQuery, isAdmin, projects]);

  return (
    <>
      <Palette open={paletteOpen} onOpenChange={setPaletteOpen} commands={commands} />
      <ShortcutSheet open={sheetOpen} onOpenChange={setSheetOpen} />
      <UndoNotice />
    </>
  );
}
