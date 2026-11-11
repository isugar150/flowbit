import type { Node as ProseMirrorNode } from "@tiptap/pm/model";
import { Plugin, PluginKey } from "@tiptap/pm/state";
import { Decoration, DecorationSet } from "@tiptap/pm/view";

export const DEFAULT_GALLERY_HEIGHT = 240;

export function limitGalleryHeight(height: number) {
  return Math.max(96, Math.min(720, Math.round(height)));
}

/** Keep images as individual top-level blocks so their comments and file references survive grouping. */
export function imageRun(doc: ProseMirrorNode, pos: number, group: string) {
  if (pos < 0 || pos >= doc.content.size) return [];
  const at = doc.resolve(pos);
  if (at.depth !== 0 || at.nodeAfter?.type.name !== "image") return [];
  const matches = (node: ProseMirrorNode) => node.type.name === "image" && node.attrs.imageGroup === group;
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

export function ungroupedImagesFrom(doc: ProseMirrorNode, pos: number) {
  if (pos < 0 || pos >= doc.content.size) return [];
  const at = doc.resolve(pos);
  if (at.depth !== 0) return [];
  const images: { pos: number; node: ProseMirrorNode }[] = [];
  for (let index = at.index(), offset = pos; index < doc.childCount; index++) {
    const node = doc.child(index);
    if (node.type.name !== "image") break;
    const group = node.attrs.imageGroup;
    if (group && imageRun(doc, offset, group).length > 1) break;
    images.push({ pos: offset, node });
    offset += node.nodeSize;
  }
  return images;
}

function decorate(doc: ProseMirrorNode) {
  const decorations: Decoration[] = [];
  let run: { pos: number; node: ProseMirrorNode }[] = [];
  function flush() {
    if (run.length >= 2) {
      const height = run[0].node.attrs.galleryHeight;
      const displayHeight = typeof height === "number" && Number.isFinite(height)
        ? limitGalleryHeight(height) : DEFAULT_GALLERY_HEIGHT;
      const hasCaption = run.some(({ node }) => Boolean(node.attrs.caption));
      run.forEach(({ pos, node }, index) => decorations.push(Decoration.node(pos, pos + node.nodeSize, {
        class: `image-gallery-item${index === 0 ? " image-gallery-start" : ""}`,
        "data-image-columns": run.length >= 3 ? "3" : "2",
        "data-gallery-caption": hasCaption ? "true" : "false",
        style: `grid-column: ${index + 1}; --gallery-height: ${displayHeight}px`,
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
