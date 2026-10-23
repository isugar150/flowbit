/** The address a share link opens at, on the site the person is using. */
export function shareUrl(origin: string, path: string): string {
  return `${origin}${path}`;
}

/** HTML for another page that shows the shared page inside it. */
export function embedCode(url: string, title: string): string {
  const escaped = title.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  return `<iframe src="${url}" title="${escaped}" width="100%" height="640" style="border:0" loading="lazy"></iframe>`;
}

/** The browser and the system a `User-Agent` names, as far as it can be told; null for what it does not say. */
export function describeClient(userAgent: string | null): { browser: string | null; os: string | null } {
  if (!userAgent) return { browser: null, os: null };
  // The order matters: Edge and Opera also say Chrome, and Chrome also says Safari.
  const browser = /Edg(?:e|A|iOS)?\//.test(userAgent)
    ? "Edge"
    : /OPR\/|Opera/.test(userAgent)
      ? "Opera"
      : /Firefox\/|FxiOS\//.test(userAgent)
        ? "Firefox"
        : /Chrome\/|CriOS\//.test(userAgent)
          ? "Chrome"
          : /Safari\//.test(userAgent)
            ? "Safari"
            : null;
  // iPhones also say "like Mac OS X", and Android also says "Linux".
  const os = /iPhone|iPad|iPod/.test(userAgent)
    ? "iOS"
    : /Android/.test(userAgent)
      ? "Android"
      : /Windows/.test(userAgent)
        ? "Windows"
        : /Mac OS X|Macintosh/.test(userAgent)
          ? "macOS"
          : /CrOS/.test(userAgent)
            ? "ChromeOS"
            : /Linux|X11/.test(userAgent)
              ? "Linux"
              : null;
  return { browser, os };
}
