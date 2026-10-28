import { updateIssue } from "./api";
import { recordUndo, undoLabel } from "./undo";
import type { IssueChanges } from "./types";

export type UndoableChange = {
  number: number;
  changes: IssueChanges;
  /** The key of the issue (FB-12) and the values to put back: when both are given, the change can be taken back. */
  key?: string;
  undoWith?: IssueChanges;
};

/** Saves a change to an issue and, if the caller said how to take it back, offers that (`Ctrl/⌘+Z`, the notice). */
export async function updateIssueUndoable(input: UndoableChange) {
  const result = await updateIssue({ number: input.number, changes: input.changes });
  const back = input.undoWith;
  if (back && Object.keys(back).length > 0) {
    recordUndo(undoLabel(input.key ?? `#${input.number}`, input.changes), () => updateIssue({ number: input.number, changes: back }));
  }
  return result;
}
