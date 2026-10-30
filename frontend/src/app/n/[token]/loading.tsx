import { messages } from "@/messages";

export default function NotificationLoading() {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-background p-6 text-foreground">
      <section className="flex w-full max-w-md flex-col gap-4 rounded-xl border bg-card p-6">
        <h1 className="text-lg font-semibold">{messages.app.notificationLink.title}</h1>
        <p role="status" className="text-sm text-muted-foreground">{messages.app.notificationLink.loading}</p>
        <div aria-hidden="true" className="h-11 w-60 animate-pulse rounded-md bg-muted" />
        <p className="text-xs text-muted-foreground">{messages.app.notificationLink.hint}</p>
      </section>
    </main>
  );
}
