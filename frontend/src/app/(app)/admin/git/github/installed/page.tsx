import type { Metadata } from "next";
import { GitHubReturn } from "@/features/git/components/github-return";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.git.title };

/** GitHub sends the browser here after the app was installed (docs/features/git-integration.md, D-146). */
export default function GitHubInstalledPage() {
  return <GitHubReturn kind="installed" />;
}
