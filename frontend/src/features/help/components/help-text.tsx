import { KeyCaps } from "@/components/shortcut-keys";

/** The inline marks of the help content (features/help/content.ts): `code`, **bold**, [[Mod+K]]. */
const MARKS = /(`[^`]+`|\*\*[^*]+\*\*|\[\[[^\]]+\]\])/;

/** A key combination such as `Mod+Enter`, as key caps. `Mod` is Ctrl, or ⌘ on a Mac. */
export function KeyCombo({ combo }: { combo: string }) {
  return <KeyCaps combo={combo} />;
}

export function HelpText({ text }: { text: string }) {
  // With a capturing group, split keeps the marks at the odd indexes.
  return text.split(MARKS).map((part, index) => {
    if (index % 2 === 0) return part;
    if (part.startsWith("**")) {
      return (
        <strong key={index} className="font-semibold">
          {part.slice(2, -2)}
        </strong>
      );
    }
    if (part.startsWith("[[")) return <KeyCombo key={index} combo={part.slice(2, -2)} />;
    return (
      <code key={index} className="rounded-md border bg-muted/60 px-1.5 py-px text-[0.92em] box-decoration-clone">
        {part.slice(1, -1)}
      </code>
    );
  });
}
