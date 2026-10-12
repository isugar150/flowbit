/**
 * Where `position: fixed` children of the editor are measured from. Normally the corner of the
 * window, but inside a dialog (which is moved into place with a transform) it is the corner of the
 * dialog, so window coordinates must be shifted by this much before they are used as top/left.
 */
export function fixedOrigin(probe: HTMLElement | null): { x: number; y: number } {
  const rect = probe?.getBoundingClientRect();
  return { x: rect?.left ?? 0, y: rect?.top ?? 0 };
}
