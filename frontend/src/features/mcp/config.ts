/** Secrets stay in the user's environment; examples never include the newly issued token. */
export function codexConfig(url: string): string {
  return `[mcp_servers.flowbit]\nurl = ${JSON.stringify(url)}\nbearer_token_env_var = "FLOWBIT_MCP_TOKEN"`;
}

export function claudeConfig(url: string): string {
  return JSON.stringify({ mcpServers: { flowbit: { type: "http", url, headers: { Authorization: "Bearer ${FLOWBIT_MCP_TOKEN}" } } } }, null, 2);
}
