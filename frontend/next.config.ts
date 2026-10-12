import type { NextConfig } from "next";

const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

/**
 * Hosts other than localhost that may open the development server, for example the PC's address
 * on the office network: FLOWBIT_DEV_ORIGINS=192.168.0.75 (comma-separated; `*` wildcards work,
 * such as 192.168.0.*). Without this, `npm run dev` refuses the page's scripts to those hosts, the
 * page never becomes interactive, and forms do nothing useful. Production (`next start`) ignores it.
 */
const devOrigins = (process.env.FLOWBIT_DEV_ORIGINS ?? "")
  .split(",")
  .map((origin) => origin.trim())
  .filter(Boolean);

const nextConfig: NextConfig = {
  // A self-contained server (.next/standalone) for the Docker image; `next dev` and `next start` are unaffected.
  output: "standalone",
  cacheComponents: true,
  allowedDevOrigins: devOrigins,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  // The browser only ever talks to this server; /api is handed to the backend so that
  // cookies are same-origin and no CORS setup is needed (docs/02-architecture.md).
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
};

export default nextConfig;
