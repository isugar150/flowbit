/** Move a block into the gap it was dropped on; gaps are counted before removing the original. */
export function moveBlock<T extends { id: string }>(items: T[], id: string, gap: number): T[] {
  const from = items.findIndex((item) => item.id === id);
  if (from < 0) return items;
  const next = items.filter((item) => item.id !== id);
  next.splice(Math.max(0, Math.min(next.length, gap > from ? gap - 1 : gap)), 0, items[from]);
  return next;
}
