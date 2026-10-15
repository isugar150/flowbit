import type { RichDoc } from "@/components/editor/rich-text-editor";

/** A node of the editor's JSON, as far as reading text out of it needs. */
type Node = { type?: string; text?: string; attrs?: Record<string, unknown>; content?: Node[] };

export type Criterion = { text: string; checked: boolean };

/** What a milestone's document says in short: its purpose and its completion criteria (D-137). */
export type MilestoneOutline = { purpose: string | null; criteria: Criterion[] };

const PURPOSE_HEADING = /^(목적|목표|purpose|goal)/i;
const CRITERIA_HEADING = /^(완료\s*조건|완료\s*기준|done|definition of done)/i;
const PURPOSE_MAX = 160;

function textOf(node: Node): string {
  if (node.type === "text") return node.text ?? "";
  if (node.type === "mention") return `@${String(node.attrs?.label ?? "")}`;
  return (node.content ?? []).map(textOf).join(node.type === "paragraph" ? "" : " ");
}

const URL_PATTERN = /https?:\/\/[^\s]+/gi;

/** A long address says nothing in a summary: only its site is kept ("figma.com"). */
function hostOf(address: string): string {
  try {
    return new URL(address).hostname.replace(/^www\./, "");
  } catch {
    return address;
  }
}

function clip(text: string): string {
  const one = text.replace(URL_PATTERN, hostOf).replace(/\s+/g, " ").trim();
  return one.length > PURPOSE_MAX ? `${one.slice(0, PURPOSE_MAX - 1)}…` : one;
}

function criteriaOf(list: Node): Criterion[] {
  return (list.content ?? [])
    .filter((item) => item.type === "taskItem")
    .map((item) => ({
      // Only the item's own first paragraph; nested lists are details.
      text: clip(textOf((item.content ?? []).find((child) => child.type === "paragraph") ?? {})),
      checked: item.attrs?.checked === true,
    }))
    .filter((item) => item.text.length > 0);
}

/**
 * Reads the purpose and the completion criteria out of a milestone's document. With the template's
 * headings ("목적", "완료 조건") it takes what is under them; without, the first paragraph and the
 * first checklist.
 */
export function milestoneOutline(doc: RichDoc | null | undefined): MilestoneOutline {
  const blocks = ((doc as Node | null | undefined)?.content ?? []) as Node[];
  let section: "purpose" | "criteria" | "other" | null = null;
  let purpose: string | null = null;
  let firstParagraph: string | null = null;
  let criteria: Criterion[] | null = null;
  let firstChecklist: Criterion[] | null = null;

  for (const block of blocks) {
    if (block.type === "heading") {
      const title = textOf(block).trim();
      section = PURPOSE_HEADING.test(title) ? "purpose" : CRITERIA_HEADING.test(title) ? "criteria" : "other";
      continue;
    }
    if (block.type === "paragraph") {
      const text = clip(textOf(block));
      if (!text) continue;
      if (section === "purpose" && purpose === null) purpose = text;
      if (firstParagraph === null && section !== "criteria") firstParagraph = text;
    } else if (block.type === "taskList") {
      const items = criteriaOf(block);
      if (items.length === 0) continue;
      if (section === "criteria" && criteria === null) criteria = items;
      firstChecklist ??= items;
    }
  }
  return { purpose: purpose ?? firstParagraph, criteria: criteria ?? firstChecklist ?? [] };
}

/** Whether there is anything to show: a purpose or at least one criterion. */
export function hasOutline(outline: MilestoneOutline): boolean {
  return outline.purpose !== null || outline.criteria.length > 0;
}

/**
 * What a new milestone's document starts with, so it is clear what to write (D-137). Headings only
 * and empty lines; nothing is saved until someone types.
 */
export function milestoneTemplate(headings: { purpose: string; criteria: string; scope: string; notes: string }): RichDoc {
  const heading = (text: string): Node => ({ type: "heading", attrs: { level: 3 }, content: [{ type: "text", text }] });
  return {
    type: "doc",
    content: [
      heading(headings.purpose),
      { type: "paragraph" },
      heading(headings.criteria),
      { type: "taskList", content: [{ type: "taskItem", attrs: { checked: false }, content: [{ type: "paragraph" }] }] },
      heading(headings.scope),
      { type: "paragraph" },
      heading(headings.notes),
      { type: "paragraph" },
    ],
  } as RichDoc;
}
