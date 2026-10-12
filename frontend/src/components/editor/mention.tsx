"use client";

import { createContext, useContext } from "react";
import { mergeAttributes, Node } from "@tiptap/core";
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from "@tiptap/react";
import { Person } from "@/components/person";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

/** Someone who can be tagged with `@` in the editor. */
export type MentionPerson = { id: string; name: string; loginId: string; avatarUrl?: string | null };

/** The people the editor knows, by id: who a tag in the text is, as of now. Given by RichTextEditor. */
export const MentionPeopleContext = createContext<ReadonlyMap<string, MentionPerson>>(new Map());

/**
 * A tag as it shows in the text: a chip with the person's name. Pointing at it opens a card with their picture, name and login id. Someone the editor
 * does not know (no longer in the project) shows under the name they were tagged with.
 */
function MentionChip({ node }: NodeViewProps) {
  const id = String(node.attrs.id);
  const label = String(node.attrs.label ?? "");
  const person = useContext(MentionPeopleContext).get(id);
  const name = person?.name ?? label;
  const loginId = person?.loginId ?? label;

  return (
    <NodeViewWrapper as="span">
      <Tooltip>
        <TooltipTrigger
          render={
            <span
              data-mention={id}
              className="cursor-default rounded bg-primary/10 px-1 py-px font-medium whitespace-nowrap text-primary hover:bg-primary/15"
            />
          }
        >
          @{name}
        </TooltipTrigger>
        <TooltipContent
          side="top"
          align="start"
          className="max-w-72 bg-popover p-3 text-sm text-popover-foreground shadow-md ring-1 ring-foreground/10"
        >
          <Person person={{ loginId, displayName: name === loginId ? null : name, avatarUrl: person?.avatarUrl ?? null }} />
        </TooltipContent>
      </Tooltip>
    </NodeViewWrapper>
  );
}

/**
 * A person tagged in the text with `@`. The node keeps the person's id (whom the server tells)
 * and their name as it was when they were tagged (what plain text and notifications say).
 */
export const Mention = Node.create({
  name: "mention",
  group: "inline",
  inline: true,
  atom: true,
  selectable: false,

  addAttributes() {
    return {
      id: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-mention"),
        renderHTML: (attributes) => ({ "data-mention": attributes.id }),
      },
      label: {
        default: "",
        parseHTML: (element) => (element.textContent ?? "").replace(/^@/, ""),
        renderHTML: () => ({}),
      },
    };
  },

  parseHTML() {
    return [{ tag: "span[data-mention]" }];
  },

  // What copying out of the editor carries; on screen the chip above is drawn instead.
  renderHTML({ node, HTMLAttributes }) {
    return ["span", mergeAttributes(HTMLAttributes), `@${node.attrs.label}`];
  },

  renderText({ node }) {
    return `@${node.attrs.label}`;
  },

  addNodeView() {
    return ReactNodeViewRenderer(MentionChip, { as: "span" });
  },
});
