"use client";

import { useId, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { CheckSetting, TextSetting } from "@/components/setting-fields";
import { SettingsActions } from "@/components/settings-card";
import { FormAlert } from "@/features/auth/components/form-alert";
import { apiErrorMessage, fieldErrorsOf } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { exportBackup, restoreBackup } from "../api";
import {
  BACKUP_FORMAT,
  BACKUP_SECTIONS,
  type BackupFile,
  type BackupSection,
} from "../types";

const m = messages.backup;

function Status({ children }: { children: React.ReactNode }) {
  return (
    <p
      role="status"
      className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5"
    >
      {children}
    </p>
  );
}

/** Which of the sections are ticked, as the checkboxes of both forms. */
function SectionChecks({
  idPrefix,
  available,
  chosen,
  hints,
  onChange,
}: {
  idPrefix: string;
  available: BackupSection[];
  chosen: BackupSection[];
  hints: Record<BackupSection, string>;
  onChange: (chosen: BackupSection[]) => void;
}) {
  return (
    <fieldset className="flex flex-col gap-3">
      <legend className="mb-3 text-[0.8125rem] leading-5 font-semibold">
        {m.sectionsLabel}
      </legend>
      {available.map((section) => (
        <CheckSetting
          key={section}
          id={`${idPrefix}-${section}`}
          label={m.sections[section]}
          hint={hints[section]}
          checked={chosen.includes(section)}
          onChange={(checked) =>
            onChange(
              BACKUP_SECTIONS.filter((s) =>
                s === section ? checked : chosen.includes(s),
              ),
            )
          }
        />
      ))}
    </fieldset>
  );
}

/** Hands the browser a file to save. */
function download(file: BackupFile) {
  // The time on the administrator's own clock, as 20261013-1020.
  const at = new Date(file.createdAt);
  const two = (value: number) => String(value).padStart(2, "0");
  const stamp = `${at.getFullYear()}${two(at.getMonth() + 1)}${two(at.getDate())}-${two(at.getHours())}${two(at.getMinutes())}`;
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(file, null, 2)], { type: "application/json" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = `flowbit-backup-${stamp}.json`;
  link.click();
  URL.revokeObjectURL(url);
}

/** Choosing what to put in a backup file and downloading it. */
export function BackupExportForm() {
  const id = useId();
  const [sections, setSections] = useState<BackupSection[]>(BACKUP_SECTIONS);
  const [password, setPassword] = useState("");
  const save = useMutation({ mutationFn: exportBackup, onSuccess: download });
  const errors = fieldErrorsOf(save.error);

  return (
    <form
      noValidate
      className="flex flex-col gap-6"
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate({ sections, password });
      }}
    >
      {save.error && !errors.password ? (
        <FormAlert>{apiErrorMessage(save.error)}</FormAlert>
      ) : null}
      <SectionChecks
        idPrefix={`${id}-export`}
        available={BACKUP_SECTIONS}
        chosen={sections}
        hints={m.exportHints}
        onChange={(next) => {
          setSections(next);
          save.reset();
        }}
      />
      <TextSetting
        id={`${id}-export-password`}
        type="password"
        label={m.password}
        hint={m.exportPasswordHint}
        value={password}
        error={errors.password}
        onChange={(value) => {
          setPassword(value);
          save.reset();
        }}
      />
      {save.isSuccess ? (
        <Status>{password ? m.exportedEncrypted : m.exportedPlain}</Status>
      ) : null}
      <SettingsActions>
        <Button
          type="submit"
          className="h-9 max-md:h-11"
          disabled={save.isPending || sections.length === 0}
        >
          {save.isPending ? (
            <Spinner data-icon="inline-start" aria-hidden="true" />
          ) : null}
          {save.isPending ? m.exporting : m.export}
        </Button>
      </SettingsActions>
    </form>
  );
}

/** What a chosen file turned out to be: a backup this page can send, or why it cannot. */
type Picked = { file: BackupFile } | { problem: string } | null;

async function readBackup(file: File): Promise<Picked> {
  try {
    const parsed: unknown = JSON.parse(await file.text());
    const candidate = parsed as Partial<BackupFile> | null;
    if (
      !candidate ||
      candidate.format !== BACKUP_FORMAT ||
      !Array.isArray(candidate.sections) ||
      typeof candidate.createdAt !== "string"
    ) {
      return { problem: m.notABackup };
    }
    return { file: candidate as BackupFile };
  } catch {
    return { problem: m.notABackup };
  }
}

/** Choosing a backup file, what to take from it, and putting it in place of the current settings. */
export function BackupRestoreForm() {
  const id = useId();
  const router = useRouter();
  const [picked, setPicked] = useState<Picked>(null);
  const [sections, setSections] = useState<BackupSection[]>([]);
  const [password, setPassword] = useState("");
  // Restoring replaces settings, so the button asks once more before it does.
  const [confirming, setConfirming] = useState(false);
  const restore = useMutation({
    mutationFn: restoreBackup,
    onSuccess: () => router.refresh(),
    onSettled: () => setConfirming(false),
  });
  const errors = fieldErrorsOf(restore.error);
  const file = picked && "file" in picked ? picked.file : null;
  const available = file
    ? BACKUP_SECTIONS.filter((s) => file.sections.includes(s))
    : [];
  const changed = () => {
    restore.reset();
    setConfirming(false);
  };

  return (
    <form
      noValidate
      className="flex flex-col gap-6"
      onSubmit={(event) => {
        event.preventDefault();
        if (!file) return;
        if (!confirming) {
          restore.reset();
          setConfirming(true);
          return;
        }
        restore.mutate({ file, sections, password });
      }}
    >
      {restore.error && !errors.password ? (
        <FormAlert>{apiErrorMessage(restore.error)}</FormAlert>
      ) : null}
      <Field data-invalid={picked && "problem" in picked ? true : undefined}>
        <FieldLabel htmlFor={`${id}-file`}>{m.file}</FieldLabel>
        <Input
          id={`${id}-file`}
          type="file"
          accept="application/json,.json"
          className="h-9 max-md:h-11"
          aria-invalid={picked && "problem" in picked ? true : undefined}
          aria-describedby={`${id}-file-note`}
          onChange={async (event) => {
            const chosen = event.target.files?.[0];
            const next = chosen ? await readBackup(chosen) : null;
            setPicked(next);
            setSections(
              next && "file" in next
                ? BACKUP_SECTIONS.filter((s) => next.file.sections.includes(s))
                : [],
            );
            setPassword("");
            changed();
          }}
        />
        {picked && "problem" in picked ? (
          <FieldError id={`${id}-file-note`}>{picked.problem}</FieldError>
        ) : (
          <FieldDescription id={`${id}-file-note`}>
            {file
              ? m.fileInfo(
                  new Date(file.createdAt).toLocaleString("ko-KR"),
                  Boolean(file.encrypted),
                )
              : m.fileHint}
          </FieldDescription>
        )}
      </Field>
      {file ? (
        <>
          <SectionChecks
            idPrefix={`${id}-restore`}
            available={available}
            chosen={sections}
            hints={m.restoreHints}
            onChange={(next) => {
              setSections(next);
              changed();
            }}
          />
          {file.encrypted ? (
            <TextSetting
              id={`${id}-restore-password`}
              type="password"
              label={m.password}
              hint={m.restorePasswordHint}
              value={password}
              error={errors.password}
              onChange={(value) => {
                setPassword(value);
                changed();
              }}
            />
          ) : null}
        </>
      ) : null}
      {restore.data ? (
        <Status>
          {m.restored(restore.data.restored.map((s) => m.sections[s]).join(", "))}
          {restore.data.restored.includes("MEMBERS")
            ? ` ${m.restoredMembers(restore.data.membersAdded, restore.data.membersSkipped)}`
            : ""}
        </Status>
      ) : null}
      {confirming ? <Status>{m.confirmRestore}</Status> : null}
      <SettingsActions>
        <Button
          type="submit"
          variant={confirming ? "destructive" : "default"}
          className="h-9 max-md:h-11"
          disabled={!file || sections.length === 0 || restore.isPending}
        >
          {restore.isPending ? (
            <Spinner data-icon="inline-start" aria-hidden="true" />
          ) : null}
          {restore.isPending
            ? m.restoring
            : confirming
              ? m.restoreConfirm
              : m.restore}
        </Button>
        {confirming && !restore.isPending ? (
          <Button
            type="button"
            variant="outline"
            className="h-9 max-md:h-11"
            onClick={() => setConfirming(false)}
          >
            {m.cancel}
          </Button>
        ) : null}
      </SettingsActions>
    </form>
  );
}
