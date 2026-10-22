/**
 * What is needed to show a text file or a table (D-187): the bytes as text, and the separator of a table. Nothing here touches the page.
 */

/**
 * The text of a file's bytes. UTF-8 (with or without the mark), UTF-16 with its mark, and when the bytes are not valid UTF-8 the
 * Korean legacy encoding (CP949) that Excel and Windows programs save in; a file in it would otherwise be all question marks.
 */
export function decodeText(bytes: ArrayBuffer): string {
  const view = new Uint8Array(bytes);
  if (view.length >= 2 && view[0] === 0xff && view[1] === 0xfe) return new TextDecoder("utf-16le").decode(view.subarray(2));
  if (view.length >= 2 && view[0] === 0xfe && view[1] === 0xff) return new TextDecoder("utf-16be").decode(view.subarray(2));
  const start = view.length >= 3 && view[0] === 0xef && view[1] === 0xbb && view[2] === 0xbf ? 3 : 0;
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(view.subarray(start));
  } catch {
    return new TextDecoder("euc-kr").decode(view.subarray(start));
  }
}

const DELIMITERS = [",", "\t", ";", "|"] as const;

/** The separator of a CSV that does not say which: the one that appears the same number of times, and often, on the first lines. */
export function detectDelimiter(text: string): string {
  const lines = text.split(/\r\n|\n|\r/).filter((line) => line !== "").slice(0, 10);
  let best: string = ",";
  let bestScore = 0;
  for (const delimiter of DELIMITERS) {
    const counts = lines.map((line) => line.split(delimiter).length - 1);
    const first = counts[0] ?? 0;
    if (first === 0) continue;
    // Lines with as many of it as the first line count; a delimiter used only in some lines is likely just text.
    const score = counts.filter((count) => count === first).length * first;
    if (score > bestScore) {
      best = delimiter;
      bestScore = score;
    }
  }
  return best;
}
