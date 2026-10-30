import { NotificationLanding } from "@/features/notifications/components/notification-landing";
import { Suspense } from "react";
import NotificationLoading from "./loading";

export const metadata = { title: "알림 열기", referrer: "no-referrer" as const };

async function NotificationTarget({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return <NotificationLanding key={token} token={token} />;
}

export default function NotificationPage({ params }: { params: Promise<{ token: string }> }) {
  return <Suspense fallback={<NotificationLoading />}><NotificationTarget params={params} /></Suspense>;
}
