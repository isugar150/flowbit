import type { Metadata } from "next";
import { MyGitHubCallback } from "@/features/git/components/my-github-account";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.git.myAccount.title };

/** GitHub sends the browser here after the member connected their account (docs/features/git-integration.md, D-150). */
export default function MyGitHubCallbackPage() {
  return <MyGitHubCallback />;
}
