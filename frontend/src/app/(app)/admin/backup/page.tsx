import type { Metadata } from "next";
import { SettingsSection } from "@/components/settings-section";
import {
  BackupExportForm,
  BackupRestoreForm,
} from "@/features/backup/components/backup-panel";
import { messages } from "@/messages";

const m = messages.backup;

export const metadata: Metadata = { title: m.title };

export default function AdminBackupPage() {
  return (
    <>
      <SettingsSection title={m.exportTitle} description={m.exportDescription}>
        <BackupExportForm />
      </SettingsSection>
      <SettingsSection title={m.restoreTitle} description={m.restoreDescription}>
        <BackupRestoreForm />
      </SettingsSection>
    </>
  );
}
