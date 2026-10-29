export type AccessToken = {
  id: string;
  name: string;
  scope: "READ" | "WRITE";
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
};

export type McpConnection = { path: string; url: string; protocolVersion: string; guidePath?: string; guideUrl?: string };
