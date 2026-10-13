import type { MetadataRoute } from "next";
import { messages } from "@/messages";

/**
 * Lets the browser install Flowbit as an app in its own window (D-110). Installing only: nothing
 * is cached for offline use. The icons come from `scripts/generate-icons.mjs`.
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
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
