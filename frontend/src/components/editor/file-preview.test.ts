import { describe, expect, it } from "vitest";
import { MAX_PREVIEW_BYTES, MAX_TEXT_PREVIEW_BYTES, isPreviewable, isTextKind, previewKindOf } from "./file-preview";

describe("previewKindOf", () => {
  it("reads the kind from the extension, whatever its case", () => {
    expect(previewKindOf("보고서.docx")).toBe("docx");
    expect(previewKindOf("분기 실적.XLSX")).toBe("xlsx");
    expect(previewKindOf("발표 자료.v2.Pptx")).toBe("pptx");
    expect(previewKindOf("계약서.PDF")).toBe("pdf");
    expect(previewKindOf("메모.txt")).toBe("txt");
    expect(previewKindOf("설정.JSON")).toBe("json");
    expect(previewKindOf("export.csv")).toBe("csv");
    expect(previewKindOf("export.tsv")).toBe("tsv");
  });

  it("does not preview what the viewer cannot read", () => {
    expect(previewKindOf("old.doc")).toBeNull();
    expect(previewKindOf("old.xls")).toBeNull();
    expect(previewKindOf("old.ppt")).toBeNull();
    expect(previewKindOf("한글.hwp")).toBeNull();
    expect(previewKindOf("data.json.zip")).toBeNull();
    expect(previewKindOf("docx")).toBeNull();
    expect(previewKindOf("archive.docx.zip")).toBeNull();
    expect(previewKindOf("")).toBeNull();
  });
});

describe("isPreviewable", () => {
  it("previews a file up to the size limit and offers only a download above it", () => {
    expect(isPreviewable("a.docx", 0)).toBe(true);
    expect(isPreviewable("a.docx", MAX_PREVIEW_BYTES)).toBe(true);
    expect(isPreviewable("a.docx", MAX_PREVIEW_BYTES + 1)).toBe(false);
    expect(isPreviewable("a.zip", 10)).toBe(false);
  });

  it("reads less of a text file than of the others, which are drawn by a viewer", () => {
    expect(isPreviewable("a.txt", MAX_TEXT_PREVIEW_BYTES)).toBe(true);
    expect(isPreviewable("a.txt", MAX_TEXT_PREVIEW_BYTES + 1)).toBe(false);
    expect(isPreviewable("a.json", MAX_TEXT_PREVIEW_BYTES)).toBe(true);
    expect(isPreviewable("a.json", MAX_TEXT_PREVIEW_BYTES + 1)).toBe(false);
    expect(isPreviewable("a.csv", MAX_TEXT_PREVIEW_BYTES + 1)).toBe(true);
    expect(isPreviewable("a.pdf", MAX_PREVIEW_BYTES)).toBe(true);
    expect(isPreviewable("a.pdf", MAX_PREVIEW_BYTES + 1)).toBe(false);
    expect(isTextKind("txt")).toBe(true);
    expect(isTextKind("json")).toBe(true);
    expect(isTextKind("csv")).toBe(false);
  });
});
