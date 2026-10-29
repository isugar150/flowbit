"use client";

import { cloneElement, type HTMLAttributes, type ReactElement } from "react";
import { personName } from "@/components/person";
import { messages } from "@/messages";
import { useIssueViewers } from "../realtime-provider";

/** A live outline and a name on a ticket; it does not add a DOM wrapper or take pointer events. */
export function IssueViewing({ number, children, label = true }: {
  number: number;
  label?: boolean;
  children: ReactElement<HTMLAttributes<HTMLElement> & { "data-issue-viewers"?: string }>;
}) {
  const viewers = useIssueViewers(number);
  if (!viewers.length) return children;
  const names = viewers.map(personName);
  const description = messages.realtime.viewersLabel(names.join(", "));
  return cloneElement(children, { "data-issue-viewers": description, "aria-description": description, title: description }, <>
    {children.props.children}
    {label ? <IssueViewingLabel number={number} /> : null}
  </>);
}

export function IssueViewingLabel({ number }: { number: number }) {
  const viewers = useIssueViewers(number);
  if (!viewers.length) return null;
  return <span className="issue-viewing-label">{messages.realtime.ticketViewing(personName(viewers[0]), viewers.length - 1)}</span>;
}
