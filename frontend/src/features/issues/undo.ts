import type { IssueChanges } from "./types";

/**
 * The changes a person can take back with `Ctrl/⌘+Z` or the "실행 취소" in the notice (docs/features/command-palette.md, D-233).
 * A change to an issue's status, priority, assignees, dates, labels or project is recorded here with the call that puts it
 * back; the last one comes off first. Kept in this browser tab only, and cleared when the page is closed.
 */
export type UndoEntry = { id: number; label: string; run: () => Promise<unknown> };

/** What the notice at the bottom of the screen says. */
export type UndoNotice = { id: number; text: string; undoable: boolean };

const MAX_ENTRIES = 30;
let entries: UndoEntry[] = [];
let notice: UndoNotice | null = null;
let counter = 0;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((listener) => listener());
}

export function subscribeUndo(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function currentNotice(): UndoNotice | null {
  return notice;
}

export function dismissNotice() {
  notice = null;
  emit();
}

/** Records a change that can be taken back, and tells the person with a notice that offers it. */
export function recordUndo(label: string, run: () => Promise<unknown>) {
  counter += 1;
  entries = [...entries.slice(-(MAX_ENTRIES - 1)), { id: counter, label, run }];
  notice = { id: counter, text: label, undoable: true };
  emit();
}

/**
 * Takes the last recorded change back. Returns false when there is nothing to take back; a failed call puts the entry
 * back so that it can be tried again, and the error is thrown.
 */
export async function undoLast(): Promise<boolean> {
  const entry = entries.at(-1);
  if (!entry) return false;
  entries = entries.slice(0, -1);
  try {
    await entry.run();
  } catch (error) {
    entries = [...entries, entry];
    throw error;
  }
  counter += 1;
  notice = { id: counter, text: entry.label, undoable: false };
  emit();
  return true;
}

export function hasUndo(): boolean {
  return entries.length > 0;
}

/** What an issue's change is called in the notice that offers to take it back. */
const NAMES: [keyof IssueChanges, string][] = [
  ["statusId", "상태"],
  ["status", "상태"],
  ["priority", "우선순위"],
  ["assigneeIds", "담당자"],
  ["startDate", "기한"],
  ["dueDate", "기한"],
  ["labelIds", "라벨"],
  ["projectId", "프로젝트"],
  ["estimate", "추정치"],
  ["cycleId", "사이클"],
];

/** "FB-12 상태 변경": the notice of a change to the issue with this key. */
export function undoLabel(key: string, changes: IssueChanges): string {
  const names = [...new Set(NAMES.filter(([field]) => field in changes).map(([, name]) => name))];
  return names.length > 0 ? `${key} ${names.join("·")} 변경` : `${key} 변경`;
}

/** The values the fields in `changes` have in `current`: what putting them back means. */
export function previousOf<T extends object>(current: T, changes: object): Partial<T> {
  const previous: Partial<T> = {};
  for (const field of Object.keys(changes) as (keyof T)[]) {
    if (field in current) previous[field] = current[field];
  }
  return previous;
}
