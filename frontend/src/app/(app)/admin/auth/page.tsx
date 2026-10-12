import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { SettingsSection } from "@/components/settings-section";
import { ExternalAuthSettings } from "@/features/external-auth/components/external-auth-settings";
import type { LdapSettings, SsoSettings } from "@/features/external-auth/types";
import { serverGet } from "@/lib/current-user";
import { messages } from "@/messages";

const m = messages.externalAuth;

export const metadata: Metadata = { title: m.title };

export default async function AdminAuthPage() {
  const [ldap, sso] = await Promise.all([
    serverGet<LdapSettings>("/api/v1/admin/auth/ldap"),
    serverGet<SsoSettings>("/api/v1/admin/auth/sso"),
  ]);
  if (!ldap || !sso) notFound();

  return (
    <SettingsSection title={m.title} description={m.description}>
      <ExternalAuthSettings ldap={ldap} sso={sso} />
    </SettingsSection>
  );
}
