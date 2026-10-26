"use client";

import { useState, useSyncExternalStore } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { FieldDescription, FieldGroup } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { CheckSetting, TextSetting } from "@/components/setting-fields";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { saveMyNotifications } from "../api";
import { DailySummarySettings } from "./daily-summary-settings";
import { disablePush, enablePush, PushError, pushState } from "../push";
import type {
  MyNotificationSettings as Settings,
  PushClientConfig,
} from "../types";

const n = messages.notifications;
const m = n.mine;

/** The signed-in person's own choices: where mail goes, and push on this browser. */
export function MyNotificationSettings({ settings }: { settings: Settings }) {
  const [email, setEmail] = useState(settings.email ?? "");
  const [notifyEmail, setNotifyEmail] = useState(settings.notifyEmail);
  const save = useMutation({ mutationFn: saveMyNotifications });
  const errors = fieldErrorsOf(save.error);

  return (
    <div className="flex flex-col gap-8">
      <form
        noValidate
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          save.mutate({ email: email.trim(), notifyEmail });
        }}
      >
        {save.isError && !errors.email ? (
          <FormAlert>{apiErrorMessage(save.error)}</FormAlert>
        ) : null}
        <FieldGroup>
          <TextSetting
            id="notify-email"
            label={m.email}
            hint={
              settings.mailAvailable
                ? m.emailHint
                : `${m.emailHint} ${m.mailUnavailable}`
            }
            value={email}
            error={errors.email}
            onChange={(value) => {
              setEmail(value);
              save.reset();
            }}
          />
          {/* Nothing to switch while the administrator has not set mail up; the saved choice is kept as it is. */}
          {settings.mailAvailable ? (
            <CheckSetting
              id="notify-email-on"
              label={m.notifyEmail}
              checked={notifyEmail}
              onChange={(value) => {
                setNotifyEmail(value);
                save.reset();
              }}
            />
          ) : null}
        </FieldGroup>
        <div className="flex items-center gap-2">
          <Button
            type="submit"
            variant="outline"
            className="h-9 max-md:h-11"
            disabled={save.isPending}
          >
            {save.isPending ? (
              <Spinner data-icon="inline-start" aria-hidden="true" />
            ) : null}
            {save.isPending ? n.saving : n.save}
          </Button>
          {save.isSuccess ? (
            <FieldDescription role="status">{n.saved}</FieldDescription>
          ) : null}
        </div>
      </form>
      <PushSetting config={settings.push} />
      <DailySummary />
    </div>
  );
}

/** The person's own daily summary (D-221): on or off, and at what time. An administrator can set it for them too. */
function DailySummary() {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <h3 className="font-medium">{messages.dailySummary.mineTitle}</h3>
        <p className="text-muted-foreground">{messages.dailySummary.mineDescription}</p>
      </div>
      <DailySummarySettings target={{ kind: "me" }} />
    </div>
  );
}

function PushSetting({ config }: { config: PushClientConfig | null }) {
  const on = useSyncExternalStore(
    pushState.subscribe,
    pushState.isOn,
    pushState.isOnServer,
  );
  const toggle = useMutation({
    mutationFn: () => (on ? disablePush(config) : enablePush(checked(config))),
  });
  const problem = toggle.isError ? pushProblem(toggle.error) : null;

  // Hidden while the administrator has not set push up. A browser that still has it on from
  // before keeps the section, so it can be turned off.
  if (!config && !on) return null;

  return (
    <div className="flex flex-col gap-2">
      <h3 className="font-medium">{m.pushTitle}</h3>
      <p className="text-muted-foreground">
        {config || on ? m.pushHint : m.pushUnavailable}
      </p>
      {config || on ? (
        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            variant="outline"
            className="h-9 max-md:h-11"
            disabled={toggle.isPending}
            onClick={() => toggle.mutate()}
          >
            {toggle.isPending ? (
              <Spinner data-icon="inline-start" aria-hidden="true" />
            ) : null}
            {toggle.isPending
              ? m.pushWorking
              : on
                ? m.pushDisable
                : m.pushEnable}
          </Button>
          {on && !toggle.isPending ? (
            <FieldDescription role="status">{m.pushOn}</FieldDescription>
          ) : null}
        </div>
      ) : null}
      {problem ? <FormAlert>{problem}</FormAlert> : null}
    </div>
  );
}

function checked(config: PushClientConfig | null): PushClientConfig {
  if (!config) throw new PushError("failed");
  return config;
}

function pushProblem(error: unknown): string {
  if (error instanceof PushError) {
    if (error.reason === "unsupported") return m.pushUnsupported;
    if (error.reason === "denied") return m.pushDenied;
    return m.pushFailed;
  }
  return apiErrorMessage(error);
}
