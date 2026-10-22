import { describe, expect, it } from "vitest";
import { decodeText, detectDelimiter } from "./text-file";

const bytes = (...values: number[]) => new Uint8Array(values).buffer;

describe("decodeText", () => {
  it("reads UTF-8 with and without its mark", () => {
    const korean = new TextEncoder().encode("한글 abc");
    expect(decodeText(korean.buffer)).toBe("한글 abc");
    const marked = new Uint8Array([0xef, 0xbb, 0xbf, ...korean]);
    expect(decodeText(marked.buffer)).toBe("한글 abc");
  });

  it("reads UTF-16 by its mark", () => {
    expect(decodeText(bytes(0xff, 0xfe, 0x5c, 0xd5, 0x00, 0xae))).toBe("한글");
    expect(decodeText(bytes(0xfe, 0xff, 0xd5, 0x5c, 0xae, 0x00))).toBe("한글");
  });

  it("falls back to the Korean legacy encoding when the bytes are not UTF-8", () => {
    // "한글" in CP949 (EUC-KR): C7 D1 B1 DB.
    expect(decodeText(bytes(0xc7, 0xd1, 0xb1, 0xdb))).toBe("한글");
  });
});

describe("detectDelimiter", () => {
  it("finds the separator the lines agree on", () => {
    expect(detectDelimiter("a,b,c\n1,2,3\n4,5,6")).toBe(",");
    expect(detectDelimiter("a;b;c\n1;2;3")).toBe(";");
    expect(detectDelimiter("a\tb\tc\n1\t2\t3")).toBe("\t");
    expect(detectDelimiter("a|b\n1|2")).toBe("|");
  });

  it("falls back to a comma for a single column", () => {
    expect(detectDelimiter("one\ntwo\nthree")).toBe(",");
    expect(detectDelimiter("")).toBe(",");
  });
});
