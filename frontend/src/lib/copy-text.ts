/**
 * Puts text on the clipboard. `navigator.clipboard` exists only on a secure page (https or
 * localhost); an installation opened as http://<address> has none, so the old selection-copy
 * command is tried then. `within` is where the hidden field goes: inside an open menu or dialog,
 * whose focus trap would otherwise take focus back from a field put elsewhere. Resolves to
 * whether it worked.
 */
export async function copyText(text: string, within?: Element | null): Promise<boolean> {
  if (typeof window !== "undefined" && window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Refused (no permission, page not focused): try the old way.
    }
  }
  return copyBySelection(text, within ?? document.body);
}

function copyBySelection(text: string, parent: Element): boolean {
  const field = document.createElement("textarea");
  field.value = text;
  field.setAttribute("readonly", "");
  // Out of sight but still selectable; fixed so that the page does not scroll to it.
  field.style.position = "fixed";
  field.style.top = "0";
  field.style.left = "0";
  field.style.opacity = "0";
  field.style.pointerEvents = "none";
  parent.appendChild(field);
  const focused = document.activeElement as HTMLElement | null;
  try {
    field.focus({ preventScroll: true });
    field.select();
    field.setSelectionRange(0, text.length);
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    field.remove();
    focused?.focus({ preventScroll: true });
  }
}
