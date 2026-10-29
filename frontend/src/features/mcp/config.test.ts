import { describe, expect, it } from "vitest";
import { claudeConfig, codexConfig } from "./config";

describe("MCP 연결 설정", () => {
  it("두 도구가 같은 주소와 환경 변수로 인증한다", () => {
    const url = "https://flowbit.example.com/api/v1/mcp";
    expect(codexConfig(url)).toContain(`url = "${url}"`);
    expect(codexConfig(url)).toContain('bearer_token_env_var = "FLOWBIT_MCP_TOKEN"');
    expect(JSON.parse(claudeConfig(url)).mcpServers.flowbit).toEqual({ type: "http", url, headers: { Authorization: "Bearer ${FLOWBIT_MCP_TOKEN}" } });
  });

  it("설정 주소의 따옴표와 줄바꿈을 이스케이프한다", () => {
    const url = 'https://example.com/"\nextra';
    expect(codexConfig(url).split("\n")).toHaveLength(3);
    expect(JSON.parse(claudeConfig(url)).mcpServers.flowbit.url).toBe(url);
  });
});
