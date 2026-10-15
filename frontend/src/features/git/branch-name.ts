const MAX_LENGTH = 60;

/**
 * The branch name to start work on an issue with (docs/features/git-integration.md "브랜치 이름
 * 복사"): `<login id>/fb-12-<title slug>`, 60 characters at most. The slug keeps only Latin letters
 * and digits joined by "-", so a Korean title leaves just `kimjm/fb-12`. A login id that is an
 * e-mail address gives its part before "@".
 */
export function branchName(loginId: string, issueKey: string, title: string): string {
  const owner =
    loginId
      .split("@")[0]
      .toLowerCase()
      .replace(/[^a-z0-9._-]+/g, "-")
      .replace(/^[-.]+|[-.]+$/g, "") || "me";
  const base = `${owner}/${issueKey.toLowerCase()}`;
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  const room = MAX_LENGTH - base.length - 1;
  const cut = room > 0 ? slug.slice(0, room).replace(/-+$/, "") : "";
  return cut ? `${base}-${cut}` : base;
}
