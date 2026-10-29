import { describe, expect, it } from "vitest";
import { decodeText, detectDelimiter, formatJson } from "./text-file";

const bytes = (...values: number[]) => new Uint8Array(values).buffer;

describe("JSON 미리보기 정돈", () => {
  it("중첩 객체와 배열에 2칸 들여쓰기를 적용하고 빈 컨테이너는 한 줄로 표시한다", () => {
    expect(formatJson('{"이름":"한글","items":[true,null,{},[]]}', 1000)).toEqual({
      text: '{\n  "이름": "한글",\n  "items": [\n    true,\n    null,\n    {},\n    []\n  ]\n}', valid: true, truncated: false,
    });
  });

  it("큰 숫자와 숫자 표기 및 키 순서를 손실 없이 보존한다", () => {
    const result = formatJson('{"2":900719925474099312345,"1":1.2300e+400,"2":-0}', 1000);
    expect(result.text).toBe('{\n  "2": 900719925474099312345,\n  "1": 1.2300e+400,\n  "2": -0\n}');
    expect(result.valid).toBe(true);
  });

  it("문자열 안의 따옴표와 괄호 및 줄바꿈 이스케이프를 변경하지 않는다", () => {
    const source = String.raw`{"text":"a\"b\\c\n,[]{}","html":"<script>alert(1)</script>"}`;
    const result = formatJson(source, 1000);
    expect(result.valid).toBe(true);
    expect(JSON.parse(result.text)).toEqual(JSON.parse(source));
    expect(result.text).toContain(String.raw`"a\"b\\c\n,[]{}"`);
  });

  it("스칼라 JSON도 표시하고 UTF-8 BOM을 해석한 파일도 정돈한다", () => {
    for (const source of ['null', 'false', '123', '"hello"', '[]', '{}']) expect(formatJson(source, 1000).text).toBe(source);
    const bytes = new Uint8Array([0xef, 0xbb, 0xbf, ...new TextEncoder().encode('{"ok":true}')]);
    expect(formatJson(decodeText(bytes.buffer), 1000).text).toBe('{\n  "ok": true\n}');
  });

  it("잘못된 JSON은 원문을 보존하고 원문과 정돈 결과 모두 표시 한도를 지킨다", () => {
    expect(formatJson('{"bad":}', 1000)).toEqual({ text: '{"bad":}', valid: false, truncated: false });
    expect(formatJson('not json', 3)).toEqual({ text: 'not', valid: false, truncated: true });
    expect(formatJson('{"a":[1,2,3]}', 10)).toEqual({ text: '{\n  "a": [', valid: true, truncated: true });
    const deep = '['.repeat(2000) + '0' + ']'.repeat(2000);
    const result = formatJson(deep, 1000);
    expect(result.text).toHaveLength(1000);
    expect(result.truncated).toBe(true);
  });
});

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
