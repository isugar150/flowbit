import type { Announcements, ScreenReaderInstructions } from "@dnd-kit/core";

/**
 * What the dnd-kit contexts of the app (the board, the calendar, the editor's block handle) say to screen readers, in Korean.
 * Everything that is dragged also has a way without the mouse: the status button on a board card, the dates in an issue's
 * properties, the arrow keys of the editor's block menu.
 */
const announcements: Announcements = {
  onDragStart: () => "집었습니다.",
  onDragOver: () => undefined,
  onDragEnd: () => "놓았습니다.",
  onDragCancel: () => "취소했습니다.",
};

const screenReaderInstructions: ScreenReaderInstructions = {
  draggable: "마우스나 터치로 끌어서 옮길 수 있습니다. 키보드로는 같은 일을 하는 버튼이나 속성 메뉴를 쓰세요.",
};

export const dndAccessibility = { announcements, screenReaderInstructions };
