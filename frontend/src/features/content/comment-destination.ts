/** Only UUID comment fragments are accepted; other anchors retain their behavior. */
export function commentDestination(path: string): { path: string; id: string } | null {
  const match = /^(\/[^#]*)#comment-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i.exec(path);
  return match ? { path: match[1], id: match[2].toLowerCase() } : null;
}

export function containsComment(entry: {
  kind: string;
  comment?: { id: string };
  replies?: { id: string }[];
  thread?: { comments: { id: string }[] };
}, id: string | null): boolean {
  return id !== null && (entry.comment?.id === id || !!entry.replies?.some((reply) => reply.id === id) || !!entry.thread?.comments.some((comment) => comment.id === id));
}
