// Service worker for web push (docs/features/M7a-notification-channels.md, "웹 푸시").
// It only shows the notification the server sent and opens the issue when it is clicked. It does
// not cache anything and does not intercept requests.

self.addEventListener("push", (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    // Not JSON: show a generic notification rather than nothing.
  }
  const notification = payload.notification ?? {};
  const path = payload.data?.path ?? "/";
  event.waitUntil(
    self.registration.showNotification(notification.title ?? "Flowbit", {
      body: notification.body ?? "",
      // Later changes to the same issue replace the earlier notification instead of piling up.
      tag: notification.tag,
      data: { path },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const path = String(event.notification.data?.path ?? "/");
  // Only a path on this site is opened, whatever the message said.
  const url = new URL(path.startsWith("/") && !path.startsWith("//") ? path : "/", self.location.origin).href;
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((windows) => {
      const open = windows.find((client) => "focus" in client);
      if (!open) return self.clients.openWindow(url);
      return open.focus().then((client) => ("navigate" in client ? client.navigate(url) : undefined));
    }),
  );
});
