/**
 * Every keyboard shortcut of the app, in one place (docs/features/command-palette.md, D-233). The command palette, the
 * shortcut sheet (`?`), the key caps shown beside buttons and the help page's table all read this list, so a shortcut is
 * never written down twice. The code that listens for the keys (`features/command/components/command-center.tsx`, the
 * editor, the dialogs) must keep to it.
 *
 * A combo is keys joined with `+` (`Mod+K`; `Mod` is Ctrl, or ⌘ on a Mac). Keys pressed one after the other are
 * separated by a space (`G M`: press G, then M).
 */
export type ShortcutGroup = "everywhere" | "go" | "list" | "editor" | "tour";

export type ShortcutDef = {
  id: string;
  /** The combos that do it; the first is the one shown beside buttons. */
  keys: string[];
  group: ShortcutGroup;
  label: string;
};

export const SHORTCUT_GROUPS: { id: ShortcutGroup; title: string }[] = [
  { id: "everywhere", title: "어디서나" },
  { id: "go", title: "이동 (G 를 누르고 이어서)" },
  { id: "list", title: "창과 목록 안에서" },
  { id: "editor", title: "편집기" },
  { id: "tour", title: "둘러보기" },
];

export const SHORTCUTS: ShortcutDef[] = [
  { id: "palette", keys: ["Mod+K"], group: "everywhere", label: "명령 팔레트 열기 (할 일과 이동을 이름으로 찾기)" },
  { id: "search", keys: ["/"], group: "everywhere", label: "티켓·문서 검색 창 열기" },
  { id: "new-issue", keys: ["C"], group: "everywhere", label: "새 티켓 만들기" },
  { id: "undo", keys: ["Mod+Z"], group: "everywhere", label: "방금 한 변경 실행 취소 (상태·우선순위·담당자·날짜 등)" },
  { id: "help", keys: ["?"], group: "everywhere", label: "단축키 모아 보기" },
  { id: "close", keys: ["Esc"], group: "everywhere", label: "창이나 메뉴 닫기" },
  { id: "go-my", keys: ["G M"], group: "go", label: "내 티켓" },
  { id: "go-created", keys: ["G R"], group: "go", label: "요청한 티켓" },
  { id: "go-all", keys: ["G I"], group: "go", label: "전체 티켓" },
  { id: "go-triage", keys: ["G T"], group: "go", label: "트리아지" },
  { id: "go-projects", keys: ["G P"], group: "go", label: "전체 프로젝트" },
  { id: "go-settings", keys: ["G S"], group: "go", label: "내 설정" },
  { id: "go-help", keys: ["G H"], group: "go", label: "도움말" },
  { id: "submit", keys: ["Mod+Enter"], group: "list", label: "새 티켓 만들기, 댓글 남기기, 프로젝트 업데이트 남기기" },
  { id: "move", keys: ["↑", "↓"], group: "list", label: "검색 결과, 명령, 블록 메뉴, 사람·라벨 목록에서 항목 이동" },
  { id: "pick", keys: ["Enter"], group: "list", label: "선택한 항목 열기·넣기" },
  { id: "editor-block", keys: ["/"], group: "editor", label: "블록 넣기 메뉴" },
  { id: "editor-mention", keys: ["@"], group: "editor", label: "사람 태그" },
  { id: "editor-bold", keys: ["Mod+B"], group: "editor", label: "굵게" },
  { id: "editor-italic", keys: ["Mod+I"], group: "editor", label: "기울임" },
  { id: "editor-undo", keys: ["Mod+Z"], group: "editor", label: "글 안에서의 실행 취소" },
  { id: "tour-step", keys: ["→", "←"], group: "tour", label: "다음 단계, 이전 단계" },
  { id: "tour-skip", keys: ["Esc"], group: "tour", label: "건너뛰기" },
];

/** Where `G` and then a letter goes. `code` is the physical key, so it works while a Korean input method is on. */
export const GO_TARGETS: { code: string; shortcut: string; href: string }[] = [
  { code: "KeyM", shortcut: "go-my", href: "/my-issues" },
  { code: "KeyR", shortcut: "go-created", href: "/my-issues/created" },
  { code: "KeyI", shortcut: "go-all", href: "/issues" },
  { code: "KeyT", shortcut: "go-triage", href: "/triage" },
  { code: "KeyP", shortcut: "go-projects", href: "/" },
  { code: "KeyS", shortcut: "go-settings", href: "/settings" },
  { code: "KeyH", shortcut: "go-help", href: "/help" },
];

export function shortcutOf(id: string): ShortcutDef {
  const found = SHORTCUTS.find((shortcut) => shortcut.id === id);
  if (!found) throw new Error(`Unknown shortcut: ${id}`);
  return found;
}

/** The rows of a group, as the help page's table takes them. */
export function shortcutRows(group: ShortcutGroup): { keys: string[]; action: string }[] {
  return SHORTCUTS.filter((shortcut) => shortcut.group === group).map((shortcut) => ({ keys: shortcut.keys, action: shortcut.label }));
}
