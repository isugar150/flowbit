import os from "node:os";
import type { NextConfig } from "next";
import { PHASE_DEVELOPMENT_SERVER } from "next/constants";

const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

/**
 * Hosts other than localhost that may open the development server. Without this, `npm run dev`
 * refuses the page's scripts to those hosts, the page never becomes interactive, and menus and
 * forms do nothing. This PC's own names are always allowed, so opening it by 127.0.0.1, by the PC's
 * address on the office network or by its computer name needs no setup. FLOWBIT_DEV_ORIGINS adds
 * others, such as another PC (comma-separated; `*` wildcards work, such as 192.168.0.*).
 */
function devOrigins(): string[] {
  const own = Object.values(os.networkInterfaces())
    .flatMap((addresses) => addresses ?? [])
    .filter((address) => address.family === "IPv4" && !address.internal)
    .map((address) => address.address);
  const extra = (process.env.FLOWBIT_DEV_ORIGINS ?? "")
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean);
  return [...new Set(["127.0.0.1", os.hostname().toLowerCase(), ...own, ...extra])];
}

const nextConfig: NextConfig = {
  // A self-contained server (.next/standalone) for the Docker image; `next dev` and `next start` are unaffected.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  // What every page says to the browser. Nobody else may put Flowbit in a frame (it embeds other sites, never the other
  // way round), nothing is sniffed into another type, and the address of a page does not leak to other sites. The proxy in
  // front adds Strict-Transport-Security when there is HTTPS (docs/10-install.md).
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Content-Security-Policy", value: "frame-ancestors 'self'" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
        ],
      },
    ];
  },
  experimental: {
    // proxy.ts runs before every request, so Next reads each body into memory and cuts it at 10MB by default. An upload
    // over that arrived at the backend as a truncated form: the upload hung until the connection broke (500, "Connection has
    // been closed"). The backend refuses a part over 20MB itself (application.yml), so this is a little more than that.
    proxyClientMaxBodySize: "100mb",
  },
  // A project has no ticket list of its own (D-135): its tickets are the list of all tickets filtered to the project. Old
  // links land there with their other filters (the query is passed along). This is answered before any page renders; as a
  // page that redirected, it ran after the project's header had started to appear, and a request that header started
  // (who is on the page) was cut off by the redirect.
  async redirects() {
    return [{ source: "/project/:id/issues", destination: "/issues?project=:id", permanent: false }];
  },
  // The browser only ever talks to this server; /api is handed to the backend so that
  // cookies are same-origin and no CORS setup is needed (docs/02-architecture.md).
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
};

// Only the development server reads the list, and computing it only there keeps this PC's addresses out of
// the production build (and so out of the Docker image).
export default function config(phase: string): NextConfig {
  return {
    ...nextConfig,
    ...(phase === PHASE_DEVELOPMENT_SERVER ? { allowedDevOrigins: devOrigins() } : {}),
  };
}
