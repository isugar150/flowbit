/** The address a share link opens at, on the site the person is using. */
export function shareUrl(origin: string, path: string): string {
  return `${origin}${path}`;
}

/** HTML for another page that shows the shared page inside it. */
export function embedCode(url: string, title: string): string {
  const escaped = title.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  return `<iframe src="${url}" title="${escaped}" width="100%" height="640" style="border:0" loading="lazy"></iframe>`;
}

/** Where a page of a shared issue is: the shared issue itself is the link's own address, a sub-issue adds `?issue=13`. */
export function issueAddress(token: string, root: number, number: number): string {
  return number === root ? `/s/${token}` : `/s/${token}?issue=${number}`;
}

/** Where a document of a shared issue is, inside the same link. */
export function documentAddress(token: string, id: string): string {
  return `/s/${token}?doc=${id}`;
}
