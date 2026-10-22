import { apiBytes } from "@/lib/api/client";
import { messages } from "@/messages";
import { MAX_TEXT_CHARS, type ViewerStage } from "./file-preview";
import { decodeText } from "./text-file";

const m = messages.editor;

/** A line under the text that says there is nothing in the file, or only the beginning of it is shown. */
function note(text: string): HTMLElement {
  const line = document.createElement("p");
  line.className = "m-0 border-t border-border bg-muted px-4 py-2 text-xs text-muted-foreground";
  line.textContent = text;
  return line;
}

function textView(text: string): HTMLElement {
  const wrap = document.createElement("div");
  if (text.trim() === "") {
    wrap.append(note(m.fileViewerEmpty));
    return wrap;
  }
  const cut = text.length > MAX_TEXT_CHARS;
  const pre = document.createElement("pre");
  pre.className = "m-0 p-4 font-mono text-[13px] leading-5 break-words whitespace-pre-wrap text-foreground";
  // textContent only: what is in the file is never read as markup.
  pre.textContent = cut ? text.slice(0, MAX_TEXT_CHARS) : text;
  wrap.append(pre);
  if (cut) wrap.append(note(m.fileViewerTextCut));
  return wrap;
}

/**
 * Shows a text file as its text in `container` (D-187). Only the beginning of a long file is shown. The caller gives the
 * container its scrolling. Returns what stops it and empties the container.
 */
export function loadTextViewer(container: HTMLElement, src: string, onStage: (stage: ViewerStage) => void): () => void {
  let cancelled = false;
  (async () => {
    try {
      const text = decodeText(await apiBytes(src));
      if (cancelled) return;
      container.replaceChildren(textView(text));
      onStage("ready");
    } catch {
      if (!cancelled) onStage("failed");
    }
  })();
  return () => {
    cancelled = true;
    container.replaceChildren();
  };
}
