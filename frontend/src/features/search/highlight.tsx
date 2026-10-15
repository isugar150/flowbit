/** Splits a query into the words the server matched on (whitespace-separated, at most five). */
export function wordsOf(query: string): string[] {
  return query.trim().split(/\s+/).filter(Boolean).slice(0, 5);
}

function escape(word: string): string {
  return word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** `text` with every occurrence of the words marked, case-insensitively. */
export function Highlight({ text, words }: { text: string; words: string[] }) {
  if (words.length === 0) return <>{text}</>;
  const pattern = new RegExp(`(${words.map(escape).join("|")})`, "gi");
  const parts = text.split(pattern);
  return (
    <>
      {parts.map((part, index) =>
        index % 2 === 1 ? (
          <mark key={index} className="rounded-[3px] bg-primary/15 text-foreground">
            {part}
          </mark>
        ) : (
          part
        ),
      )}
    </>
  );
}
