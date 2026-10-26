import type { Metadata, Viewport } from "next";
import { Providers } from "@/components/providers";
import { messages } from "@/messages";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: messages.app.name, template: `%s · ${messages.app.name}` },
  description: messages.app.description,
  // iOS ignores most of the manifest; this is what makes "홈 화면에 추가" open without browser bars.
  appleWebApp: { capable: true, title: messages.app.name, statusBarStyle: "default" },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#131416" },
  ],
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // next-themes sets the theme class on <html> before hydration.
    <html lang="ko" className="h-full antialiased" suppressHydrationWarning>
      <body className="flex min-h-full flex-col">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
