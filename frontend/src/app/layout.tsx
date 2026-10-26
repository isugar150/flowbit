import type { Metadata } from "next";
import { Providers } from "@/components/providers";
import { messages } from "@/messages";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: messages.app.name, template: `%s · ${messages.app.name}` },
  description: messages.app.description,
  // iOS ignores most of the manifest; this is what makes "홈 화면에 추가" open without browser bars.
  appleWebApp: { capable: true, title: messages.app.name, statusBarStyle: "default" },
};

// The colour of the window's title bar (an installed app) and of the browser's bar on a phone is not set through `viewport.themeColor`:
// Next takes those tags out and puts them back on every navigation, and for that moment the bar falls back to white. Tags drawn by
// the layout itself stay in the document (React places them in <head>) for as long as the app is open.
const THEME_COLORS = [
  { media: "(prefers-color-scheme: light)", color: "#ffffff" },
  { media: "(prefers-color-scheme: dark)", color: "#131416" },
];

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // next-themes sets the theme class on <html> before hydration.
    <html lang="ko" className="h-full antialiased" suppressHydrationWarning>
      <head>
        {THEME_COLORS.map(({ media, color }) => (
          <meta key={media} name="theme-color" media={media} content={color} />
        ))}
      </head>
      <body className="flex min-h-full flex-col">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
