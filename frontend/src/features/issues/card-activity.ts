/** One line of an issue's recent history on a card: who, what they did (with the value when it is told), and when. */
export type CardActivity = { who: string | null; text: string; at: string };

/**
 * What a line says, from an entry's words (`describe` in components/activity-event.tsx): "님이 상태를 바꿨습니다:" and the
 * value after it when there is one ("진행 중 → 완료"). Without the value there is no colon to leave hanging, and
 * with nobody named ("님이") the line starts at what was done.
 */
export function activityText(action: string, change: string | undefined, named: boolean): string {
  const said = named ? action : action.replace(/^님이 /, "");
  return change ? `${said} ${change}` : said.replace(/:$/, "");
}
