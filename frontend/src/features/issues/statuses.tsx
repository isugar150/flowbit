"use client";

import { createContext, useContext } from "react";
import type { ChipOption } from "./components/chip-select";
import { StatusIcon } from "./meta";
import { BUILT_IN_STATUSES, type IssueStatusItem } from "./status-defaults";

const StatusesContext = createContext<IssueStatusItem[]>(BUILT_IN_STATUSES);

/** Hands the statuses (as the server sends them, by category and position) to the screens under it. */
export function IssueStatusProvider({ statuses, children }: { statuses: IssueStatusItem[] | null; children: React.ReactNode }) {
  return <StatusesContext value={statuses && statuses.length > 0 ? statuses : BUILT_IN_STATUSES}>{children}</StatusesContext>;
}

/** Every status, in the order of a menu: by category (backlog first), then by position. */
export function useIssueStatuses(): IssueStatusItem[] {
  return useContext(StatusesContext);
}

/** The options of a status menu: the value is the status id, the icon the shape of its category. */
export function statusChoices(statuses: IssueStatusItem[]): ChipOption<string>[] {
  return statuses.map((status) => ({ value: status.id, label: status.name, icon: <StatusIcon status={status.category} statusId={status.id} statusName={status.name} /> }));
}

export function useStatusOptions(): ChipOption<string>[] {
  return statusChoices(useIssueStatuses());
}
