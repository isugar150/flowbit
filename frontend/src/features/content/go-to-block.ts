/**
 * Scrolls to the block of a page that has this number (`data-block-id`, written by the editor) and
 * lights it up for a moment, so that a thread in the activity can lead the eye to what it is about.
 * Does nothing when the block is not on screen.
 */
export function goToBlock(blockId: string): void {
  const block = document.querySelector(`[data-block-id="${CSS.escape(blockId)}"]`);
  if (!(block instanceof HTMLElement)) return;
  block.scrollIntoView({ block: "center", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
  block.classList.remove("block-flash");
  // Restarts the animation when the same block is asked for twice.
  void block.offsetWidth;
  block.classList.add("block-flash");
  window.setTimeout(() => block.classList.remove("block-flash"), 1600);
}
