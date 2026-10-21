import type { Metadata } from "next";
import { SettingsSection } from "@/components/settings-section";
import { FilesOverview } from "@/features/files/components/files-overview";
import { messages } from "@/messages";

const m = messages.files;

export const metadata: Metadata = { title: m.title };

/** Storage use, and the files nothing uses (docs/features/admin-files.md). The numbers are read in the browser: they can take a while. */
export default function AdminFilesPage() {
  return (
    // Wide: the lists have several columns (a page marks itself with data-wide, see the admin layout).
    <div data-wide className="flex flex-col gap-8">
      <SettingsSection title={m.title} description={m.description}>
        <FilesOverview />
      </SettingsSection>
    </div>
  );
}
