"use client";

import { useState } from "react";
import { KeyRoundIcon, NetworkIcon } from "lucide-react";
import { SettingsCard } from "@/components/settings-card";
import { messages } from "@/messages";
import type { LdapSettings, SsoSettings } from "../types";
import { LdapSettingsForm } from "./ldap-settings-form";
import { SsoSettingsForm } from "./sso-settings-form";

const m = messages.externalAuth;

/** The sign-in integrations as a list: each row shows whether it is on and opens to its settings. */
export function ExternalAuthSettings({
  ldap,
  sso,
}: {
  ldap: LdapSettings;
  sso: SsoSettings;
}) {
  // What the badges show follows the last save, without reloading the page.
  const [ldapOn, setLdapOn] = useState(ldap.enabled);
  const [ssoOn, setSsoOn] = useState(sso.enabled);
  const status = (on: boolean) => ({ on, label: on ? m.on : m.off });

  return (
    <div className="flex flex-col gap-3">
      <SettingsCard
        icon={<NetworkIcon />}
        title={m.ldap.title}
        description={m.ldap.description}
        status={status(ldapOn)}
      >
        <LdapSettingsForm
          settings={ldap}
          onSaved={(saved) => setLdapOn(saved.enabled)}
        />
      </SettingsCard>
      <SettingsCard
        icon={<KeyRoundIcon />}
        title={m.sso.title}
        description={m.sso.description}
        status={status(ssoOn)}
      >
        <SsoSettingsForm
          settings={sso}
          onSaved={(saved) => setSsoOn(saved.enabled)}
        />
      </SettingsCard>
    </div>
  );
}
