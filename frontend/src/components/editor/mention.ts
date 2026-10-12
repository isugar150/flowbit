import { mergeAttributes, Node } from "@tiptap/core";

/** Someone who can be tagged with `@` in the editor. */
export type MentionPerson = { id: string; name: string; loginId: string };

/**
 * A person tagged in the text with `@`. The node keeps the person's id (whom the server tells)
 * and their name as it was when they were tagged, which is what the text shows.
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

  renderHTML({ node, HTMLAttributes }) {
    return ["span", mergeAttributes({ class: "mention" }, HTMLAttributes), `@${node.attrs.label}`];
  },

  renderText({ node }) {
    return `@${node.attrs.label}`;
  },
});
