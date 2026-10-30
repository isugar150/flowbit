import type { MetadataRoute } from "next";
import { headers } from "next/headers";
import { messages } from "../messages";

/**
 * Lets the browser install Flowbit as an app in its own window (D-110). Installing only: nothing
 * is cached for offline use. The icons come from `scripts/generate-icons.mjs`.
 */
export default async function manifest(): Promise<MetadataRoute.Manifest> {
  const requestHeaders = await headers();
  const host = requestHeaders.get("host") ?? "localhost";
  const forwardedProtocol = requestHeaders.get("x-forwarded-proto")?.split(",")[0].trim();
  const protocol = forwardedProtocol === "http" || forwardedProtocol === "https"
    ? forwardedProtocol
    : /^(localhost|127\.0\.0\.1|\[::1\])(:|$)/.test(host) ? "http" : "https";
  const origin = new URL(`${protocol}://${host}`).origin;
  // Newer manifest members (`handle_links` is not in Next's type yet); browsers that do not know them ignore them.
  const links = {
    // A link to this site is better opened in the installed app than in a browser tab (Chrome decides; see docs/10-install.md).
    handle_links: "preferred",
    // A link opened while the app is already running goes to that window, as a page in it, instead of a second window.
    // The window's title bar and the splash use the manifest's colours until the page says its own (the `themeColor` of the
    // layout, light and dark). With only the light ones, every navigation inside the app flashed a white bar before turning
    // dark. A browser that does not know `color_scheme_dark` keeps the light colours.
    color_scheme_dark: { theme_color: "#131416", background_color: "#131416" },
    launch_handler: { client_mode: ["navigate-existing", "auto"] as Array<"navigate-existing" | "auto"> },
    protocol_handlers: [{ protocol: "web+flowbit", url: "/open-app?url=%s" }],
    related_applications: [{ platform: "webapp", id: `${origin}/`, url: `${origin}/manifest.webmanifest` }],
    prefer_related_applications: false,
  };
  return {
    ...links,
    id: "/",
    name: messages.app.name,
    short_name: messages.app.name,
    description: messages.app.description,
    lang: "ko",
    start_url: "/",
    scope: "/",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#ffffff",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
      { src: "/icons/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
