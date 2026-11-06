import type { Node as ProseMirrorNode } from "@tiptap/pm/model";
import { Plugin, PluginKey } from "@tiptap/pm/state";
import { Decoration, DecorationSet } from "@tiptap/pm/view";

/** Keep images as individual top-level blocks so their comments and file references survive grouping. */
export function imageRun(doc: ProseMirrorNode, pos: number, group?: string) {
  if (pos < 0 || pos >= doc.content.size) return [];
  const at = doc.resolve(pos);
  if (at.depth !== 0 || at.nodeAfter?.type.name !== "image") return [];
  const matches = (node: ProseMirrorNode) => node.type.name === "image" && (group === undefined || node.attrs.imageGroup === group);
  let first = at.index();
  let last = first + 1;
  let offset = pos;
  while (first > 0 && matches(doc.child(first - 1))) {
    first--;
    offset -= doc.child(first).nodeSize;
  }
  while (last < doc.childCount && matches(doc.child(last))) last++;
  const result: { pos: number; node: ProseMirrorNode }[] = [];
  for (let index = first; index < last; index++) {
    const node = doc.child(index);
    result.push({ pos: offset, node });
    offset += node.nodeSize;
  }
  return result;
}

function decorate(doc: ProseMirrorNode) {
  const decorations: Decoration[] = [];
  let run: { pos: number; node: ProseMirrorNode }[] = [];
  function flush() {
    if (run.length >= 2) {
      run.forEach(({ pos, node }, index) => decorations.push(Decoration.node(pos, pos + node.nodeSize, {
        class: `image-gallery-item${index === 0 ? " image-gallery-start" : ""}`,
        "data-image-columns": run.length >= 3 ? "3" : "2",
      }, { imageGallery: true })));
    }
    run = [];
  }
  doc.forEach((node, pos) => {
    const group = node.type.name === "image" && node.attrs.imageGroup;
    if (!group || (run.length > 0 && run[0].node.attrs.imageGroup !== group)) flush();
    if (group) run.push({ pos, node });
  });
  flush();
  return DecorationSet.create(doc, decorations);
}

export const imageGalleryPlugin = new Plugin<DecorationSet>({
  key: new PluginKey("imageGallery"),
  state: {
    init: (_, state) => decorate(state.doc),
    apply: (transaction, previous) => transaction.docChanged ? decorate(transaction.doc) : previous,
  },
  props: { decorations(state) { return this.getState(state); } },
  view(view) {
    const resize = () => {
      const width = view.dom.clientWidth;
      const layout = width >= 760 ? "3" : width >= 480 ? "2" : "1";
      if (view.dom.dataset.imageLayout !== layout) view.dom.dataset.imageLayout = layout;
    };
    resize();
    let frame = 0;
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(resize);
    });
    observer.observe(view.dom);
    return { destroy() { observer.disconnect(); cancelAnimationFrame(frame); delete view.dom.dataset.imageLayout; } };
  },
});
