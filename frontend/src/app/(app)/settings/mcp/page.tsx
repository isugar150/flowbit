import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { McpSettings } from "@/features/mcp/components/mcp-settings";
import type { AccessToken, McpConnection } from "@/features/mcp/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

export const metadata: Metadata = { title: messages.mcp.title };

export default async function McpSettingsPage() {
  const [connection, tokens] = await Promise.all([
    serverGet<McpConnection>("/api/v1/me/mcp"),
    serverGet<AccessToken[]>("/api/v1/me/access-tokens"),
  ]);
  if (!connection || !tokens) notFound();
  return <McpSettings connection={connection} tokens={tokens} />;
}
