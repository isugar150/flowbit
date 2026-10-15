import { Node } from "@tiptap/core";
import { messages } from "@/messages";

const m = messages.editor;

export type EmbedProvider = "figma" | "outline";

export type EmbedAttrs = { provider: EmbedProvider; url: string };

/** The longest address an embed keeps (RichText.kt checks the same). */
export const MAX_EMBED_URL_LENGTH = 2000;

/**
 * Addresses that can be shown in place, by service. Keep in step with `EMBED_URLS` in RichText.kt.
 * Outline is usually installed by each organisation, so any host is accepted, but only its public
 * share links (`/s/…`): a signed-in page would not get Outline's login cookie inside a frame.
 */
const PATTERNS: Record<EmbedProvider, RegExp> = {
  figma: /^https:\/\/(?:www\.)?figma\.com\/(?:file|design|proto|board|slides|deck)\/[A-Za-z0-9]+(?:[/?#]\S*)?$/,
  outline: /^https?:\/\/[^/\s?#]+\/s\/[A-Za-z0-9_-]+(?:[/?#]\S*)?$/,
};

const PROVIDER_NAMES: Record<EmbedProvider, string> = { figma: "Figma", outline: "Outline" };

/** The embed for an address someone pasted or typed, or null when it is not one we can show. */
export function embedFor(raw: string): EmbedAttrs | null {
  const url = raw.trim();
  if (!url || url.length > MAX_EMBED_URL_LENGTH) return null;
  for (const provider of Object.keys(PATTERNS) as EmbedProvider[]) {
    if (PATTERNS[provider].test(url)) return { provider, url };
  }
  return null;
}

/** Figma's embed address (Embed Kit 2.0) for a file's own address; old `/file/` addresses are designs. */
function figmaFrameSource(url: string): string {
  const address = new URL(url);
  const [, kind, ...rest] = address.pathname.split("/");
  address.hostname = "embed.figma.com";
  address.pathname = `/${kind === "file" ? "design" : kind}/${rest.join("/")}`;
  address.searchParams.set("embed-host", "flowbit");
  return address.toString();
}

function frameSource({ provider, url }: EmbedAttrs): string {
  return provider === "figma" ? figmaFrameSource(url) : url;
}

const EXTERNAL_ICON =
  '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/></svg>';

/**
 * A Figma file or an Outline page shown in place, as a block of its own. The block keeps only the
 * address the person gave; the frame's address is worked out from it when drawn. The bar above
 * the frame is what selects and drags the block, since the frame itself takes the pointer.
 */
export const Embed = Node.create({
  name: "embed",
  group: "block",
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return { provider: { default: null }, url: { default: null } };
  },

  parseHTML() {
    return [
      {
        tag: "div[data-embed]",
        getAttrs: (element) => embedFor(element.getAttribute("data-url") ?? "") ?? false,
      },
    ];
  },

  renderHTML({ node }) {
    return ["div", { "data-embed": node.attrs.provider, "data-url": node.attrs.url }];
  },

  addNodeView() {
    return ({ node }) => {
      const attrs = embedFor(String(node.attrs.url ?? ""));
      const dom = document.createElement("div");
      dom.contentEditable = "false";
      dom.setAttribute("data-embed", attrs?.provider ?? "");
      dom.className =
        "rich-text-embed my-2 overflow-hidden rounded-lg border border-border bg-background [&.ProseMirror-selectednode]:ring-2 [&.ProseMirror-selectednode]:ring-ring";

      const bar = document.createElement("div");
      bar.className = "flex h-8 items-center gap-2 border-b border-border bg-muted/50 px-3 text-xs text-muted-foreground";
      const name = document.createElement("span");
      name.className = "shrink-0 font-medium text-foreground";
      const address = document.createElement("span");
      address.className = "min-w-0 flex-1 truncate";
      const open = document.createElement("a");
      open.target = "_blank";
      open.rel = "noopener noreferrer";
      open.className =
        "flex shrink-0 items-center gap-1 rounded px-1.5 py-0.5 text-muted-foreground no-underline hover:bg-muted hover:text-foreground";
      open.innerHTML = EXTERNAL_ICON;
      open.append(m.embedOpen);
      bar.append(name, address, open);
      dom.append(bar);

      if (attrs) {
        name.textContent = PROVIDER_NAMES[attrs.provider];
        address.textContent = attrs.url;
        open.href = attrs.url;
        const frame = document.createElement("iframe");
        frame.src = frameSource(attrs);
        frame.title = m.embedTitle(PROVIDER_NAMES[attrs.provider]);
        frame.loading = "lazy";
        frame.allowFullscreen = true;
        frame.referrerPolicy = "strict-origin-when-cross-origin";
        // The page inside may run and keep its own sign-in, but never move this page elsewhere.
        frame.setAttribute(
          "sandbox",
          "allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox allow-forms allow-storage-access-by-user-activation",
        );
        frame.className = "block h-[28rem] w-full border-0 bg-background";
        dom.append(frame);
      } else {
        name.textContent = m.embedUnknown;
        open.remove();
      }
      return { dom, update: (updated) => updated.type === node.type && updated.attrs.url === node.attrs.url };
    };
  },
});
