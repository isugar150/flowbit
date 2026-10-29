"use client";

import Link from "next/link";
import { useState, useSyncExternalStore } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { CopyIcon } from "lucide-react";
import { SettingsSection } from "@/components/settings-section";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Separator } from "@/components/ui/separator";
import { Spinner } from "@/components/ui/spinner";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiFetch } from "@/lib/api/client";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { claudeConfig, codexConfig } from "../config";
import type { AccessToken, McpConnection } from "../types";

const m = messages.mcp;
const schema = z.object({
  name: z.string().trim().min(1, m.nameRequired).max(50, m.nameLength),
  scope: z.enum(["READ", "WRITE"]),
  expiresInDays: z.number().int().min(1, m.daysRange).max(365, m.daysRange),
});
type Values = z.infer<typeof schema>;
const subscribe = () => () => {};
const clientTime = Date.now();

function CopyButton({ value, label = m.copy }: { value: string; label?: string }) {
  const [status, setStatus] = useState("");
  async function copy() {
    try { await navigator.clipboard.writeText(value); setStatus(m.copied); }
    catch { setStatus(m.copyFailed); }
  }
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button type="button" variant="outline" size="sm" onClick={copy}><CopyIcon data-icon="inline-start" />{label}</Button>
      <span role="status" className="text-xs text-muted-foreground">{status}</span>
    </div>
  );
}

export function McpSettings({ connection, tokens: initialTokens }: { connection: McpConnection; tokens: AccessToken[] }) {
  const [tokens, setTokens] = useState(initialTokens);
  const [issued, setIssued] = useState<{ token: string; item: AccessToken } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmRevoke, setConfirmRevoke] = useState<string | null>(null);
  const [client, setClient] = useState("codex");
  const browserOrigin = useSyncExternalStore(subscribe, () => window.location.origin, () => "");
  const now = useSyncExternalStore(subscribe, () => clientTime, () => 0);
  const url = connection.url.startsWith("http") ? connection.url : `${browserOrigin}${connection.path}`;
  const guideUrl = connection.guideUrl?.startsWith("http") ? connection.guideUrl : `${url}/setup`;
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { name: "", scope: "READ", expiresInDays: 30 } });
  const [creating, setCreating] = useState(false);
  const submit = form.handleSubmit(async (values) => {
    if (creating) return;
    setError(null);
    setCreating(true);
    try {
      const result = await apiFetch<{ token: string; item: AccessToken }>("/api/v1/me/access-tokens", { method: "POST", body: values });
      setIssued(result);
      setTokens((current) => [result.item, ...current]);
      form.reset();
    } catch (failure) { setError(apiErrorMessage(failure)); }
    finally { setCreating(false); }
  });

  async function revoke(item: AccessToken) {
    setConfirmRevoke(null);
    setError(null);
    setTokens((current) => current.filter((token) => token.id !== item.id));
    try {
      await apiFetch(`/api/v1/me/access-tokens/${item.id}`, { method: "DELETE" });
      if (issued?.item.id === item.id) setIssued(null);
    } catch (failure) {
      setTokens((current) => [...current, item].sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
      setError(apiErrorMessage(failure));
    }
  }

  const date = (value: string) => new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeZone: "Asia/Seoul" }).format(new Date(value));
  return (
    <>
      <SettingsSection title={m.title} description={m.description}>
        <p className="text-sm leading-6">{m.permissions}</p>
        <div className="flex min-w-0 flex-col gap-2">
          <h3 className="text-sm font-medium">{m.agentTitle}</h3>
          <p className="text-sm leading-6 text-muted-foreground">{m.agentDescription}</p>
          <code className="select-all break-all rounded-md bg-muted px-3 py-2 text-xs">{guideUrl}</code>
          <CopyButton value={guideUrl} label={m.copyGuide} />
          <a href={guideUrl} target="_blank" rel="noopener noreferrer" className="w-fit text-sm underline underline-offset-4">{m.openGuide}</a>
          <p className="text-sm leading-6 text-muted-foreground">{m.agentNote}</p>
        </div>
        <div className="flex min-w-0 flex-col gap-2">
          <h3 className="text-sm font-medium">{m.address}</h3>
          <code className="select-all break-all rounded-md bg-muted px-3 py-2 text-xs">{url}</code>
          <CopyButton value={url} label={m.copyAddress} />
        </div>
        <Link href="/help#mcp" className="w-fit text-sm underline underline-offset-4">{m.help}</Link>
      </SettingsSection>
      <Separator />
      <SettingsSection title={m.tokens} description={m.tokenDescription}>
        {error ? <FormAlert>{error}</FormAlert> : null}
        <form onSubmit={submit} noValidate>
          <FieldGroup>
            <Field data-invalid={!!form.formState.errors.name}>
              <FieldLabel htmlFor="mcp-token-name">{m.name}</FieldLabel>
              <Input id="mcp-token-name" autoComplete="off" maxLength={50} placeholder={m.namePlaceholder} {...form.register("name")} aria-invalid={!!form.formState.errors.name} />
              <FieldError>{form.formState.errors.name?.message}</FieldError>
            </Field>
            <Field>
              <FieldLabel htmlFor="mcp-token-scope">{m.scope}</FieldLabel>
              <NativeSelect id="mcp-token-scope" {...form.register("scope")}>
                <NativeSelectOption value="READ">{m.read}</NativeSelectOption>
                <NativeSelectOption value="WRITE">{m.write}</NativeSelectOption>
              </NativeSelect>
              <FieldDescription>{m.scopeHint}</FieldDescription>
            </Field>
            <Field data-invalid={!!form.formState.errors.expiresInDays}>
              <FieldLabel htmlFor="mcp-token-days">{m.days}</FieldLabel>
              <Input id="mcp-token-days" type="number" min={1} max={365} className="max-w-32" {...form.register("expiresInDays", { valueAsNumber: true })} aria-invalid={!!form.formState.errors.expiresInDays} />
              <FieldError>{form.formState.errors.expiresInDays?.message}</FieldError>
            </Field>
            <Button type="submit" variant="outline" className="w-fit" disabled={creating || issued !== null}>
              {creating ? <Spinner data-icon="inline-start" /> : null}{creating ? m.issuing : m.issue}
            </Button>
          </FieldGroup>
        </form>
        {issued ? (
          <Alert>
            <AlertTitle>{m.issued}</AlertTitle>
            <AlertDescription>
              <p>{m.once}</p>
              <code className="my-2 block select-all break-all text-xs">{issued.token}</code>
              <div className="flex flex-wrap gap-2"><CopyButton value={issued.token} label={m.copyToken} /><Button type="button" size="sm" variant="ghost" onClick={() => setIssued(null)}>{m.dismiss}</Button></div>
            </AlertDescription>
          </Alert>
        ) : null}
        <div className="flex flex-col gap-3" aria-label={m.tokenList}>
          {tokens.length === 0 ? <p className="text-sm text-muted-foreground">{m.empty}</p> : tokens.map((item) => (
            <div key={item.id} className="flex min-w-0 flex-wrap items-start justify-between gap-3 rounded-lg border p-3">
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="break-all text-sm font-medium">{item.name}</span>
                <div className="flex flex-wrap gap-2"><Badge variant="secondary">{item.scope === "READ" ? m.read : m.write}</Badge>{now > 0 && Date.parse(item.expiresAt) <= now ? <Badge variant="outline">{m.expired}</Badge> : null}</div>
                <span className="text-xs text-muted-foreground">{m.expires} {date(item.expiresAt)}</span>
                <span className="text-xs text-muted-foreground">{m.lastUsed} {item.lastUsedAt ? date(item.lastUsedAt) : m.neverUsed}</span>
              </div>
              {confirmRevoke === item.id ? (
                <div className="flex flex-col gap-2"><p className="text-xs">{m.revokeConfirm}</p><div className="flex gap-2"><Button size="sm" variant="destructive" onClick={() => revoke(item)}>{m.revoke}</Button><Button size="sm" variant="ghost" onClick={() => setConfirmRevoke(null)}>{m.cancel}</Button></div></div>
              ) : <Button size="sm" variant="outline" aria-label={`${item.name} ${m.revoke}`} onClick={() => setConfirmRevoke(item.id)}>{m.revoke}</Button>}
            </div>
          ))}
        </div>
      </SettingsSection>
      <Separator />
      <SettingsSection title={m.connect} description={m.connectDescription}>
        <Field>
          <FieldLabel htmlFor="mcp-client">{m.client}</FieldLabel>
          <NativeSelect id="mcp-client" value={client} onChange={(event) => setClient(event.target.value)}><NativeSelectOption value="codex">Codex</NativeSelectOption><NativeSelectOption value="claude">Claude Code</NativeSelectOption></NativeSelect>
        </Field>
        <p className="text-sm leading-6">{client === "codex" ? m.codexSteps : m.claudeSteps}</p>
        <pre className="min-w-0 max-w-full overflow-x-auto rounded-lg bg-muted p-3 text-xs leading-5"><code>{client === "codex" ? codexConfig(url) : claudeConfig(url)}</code></pre>
        <CopyButton value={client === "codex" ? codexConfig(url) : claudeConfig(url)} label={m.copyConfig} />
        <p className="text-sm leading-6 text-muted-foreground">{m.environment}</p>
        <p className="text-sm leading-6 text-muted-foreground">{m.connectionCheck}</p>
      </SettingsSection>
      <Separator />
      <SettingsSection title={m.usage} description={m.usageDescription}>
        <ul className="flex list-disc flex-col gap-2 pl-5 text-sm leading-6">{m.examples.map((example) => <li key={example}>{example}</li>)}</ul>
        <p className="text-sm leading-6 text-muted-foreground">{m.search}</p>
        <p className="text-sm leading-6 text-muted-foreground">{m.properties}</p>
        <p className="text-sm leading-6 text-muted-foreground">{m.triage}</p>
        <p className="text-sm leading-6 text-muted-foreground">{m.markdown}</p>
        <p className="text-sm leading-6 text-muted-foreground">{m.troubleshooting}</p>
      </SettingsSection>
    </>
  );
}
