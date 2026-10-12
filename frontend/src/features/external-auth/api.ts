import { apiFetch } from "@/lib/api/client";
import type {
  LdapSettings,
  LdapSettingsInput,
  LdapTestResult,
  SsoSettings,
  SsoSettingsInput,
} from "./types";

export function saveLdapSettings(
  input: LdapSettingsInput,
): Promise<LdapSettings> {
  return apiFetch<LdapSettings>("/api/v1/admin/auth/ldap", {
    method: "PUT",
    body: input,
  });
}

/** Tries the settings against the directory without saving them. */
export function testLdapSettings(
  input: LdapSettingsInput,
): Promise<LdapTestResult> {
  return apiFetch<LdapTestResult>("/api/v1/admin/auth/ldap/test", {
    method: "POST",
    body: input,
  });
}

export function saveSsoSettings(input: SsoSettingsInput): Promise<SsoSettings> {
  return apiFetch<SsoSettings>("/api/v1/admin/auth/sso", {
    method: "PUT",
    body: input,
  });
}
