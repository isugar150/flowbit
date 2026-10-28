import { afterEach, expect, it, vi } from "vitest";
import { randomId } from "./random-id";

afterEach(() => vi.unstubAllGlobals());

it("uses native UUID when available", () => {
  const id = "8af5d7b1-9b72-46ed-b8de-4931dbdc9f30";
  vi.stubGlobal("crypto", { randomUUID: () => id });
  expect(randomId()).toBe(id);
});

it("creates distinct valid UUIDs on HTTP without randomUUID", () => {
  const getRandomValues = globalThis.crypto.getRandomValues.bind(globalThis.crypto);
  vi.stubGlobal("crypto", { getRandomValues });
  const ids = Array.from({ length: 100 }, () => randomId());
  expect(new Set(ids).size).toBe(100);
  for (const id of ids) expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

it("can add client records even without a crypto object", () => {
  vi.stubGlobal("crypto", undefined);
  expect(randomId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});
