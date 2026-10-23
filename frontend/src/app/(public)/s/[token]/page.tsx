import { Suspense } from "react";
import type { Metadata } from "next";
import { SharedView } from "@/features/share/components/shared-view";
import { messages } from "@/messages";

// A shared page is for the people who were given its address: search engines are told to keep out.
export const metadata: Metadata = {
  title: messages.share.page.calendarLabel,
  robots: { index: false, follow: false },
};

/**
 * What a share link shows (docs/features/sharing.md): open to anyone, signed in or not, and it may
 * be shown inside a frame on another site (next.config.ts lets only `/s/*` be framed).
 */
export default function SharedPage({ params }: PageProps<"/s/[token]">) {
  return (
    // The address is read per request, so this part streams in.
    <Suspense fallback={null}>
      <SharedContent params={params} />
    </Suspense>
  );
}

async function SharedContent({ params }: { params: PageProps<"/s/[token]">["params"] }) {
  const { token } = await params;
  return <SharedView token={token} />;
}
