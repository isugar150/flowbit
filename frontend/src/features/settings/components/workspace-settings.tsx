"use client";

import { FieldGroup } from "@/components/ui/field";
import { initials } from "@/lib/initials";
import { messages } from "@/messages";
import { removeWorkspaceLogo, renameWorkspace, setIssueKey, uploadWorkspaceLogo } from "../api";
import type { Workspace } from "../types";
import { ImageUploadField } from "./image-upload-field";
import { NameForm } from "./name-form";

const m = messages.settings.workspace;

export function WorkspaceSettings({ workspace }: { workspace: Workspace }) {
  return (
    <FieldGroup>
      <ImageUploadField
        id="logo"
        label={m.imageLabel}
        imageUrl={workspace.logoUrl}
        imageAlt={m.imageAlt}
        fallback={initials(workspace.name)}
        shape="square"
        upload={uploadWorkspaceLogo}
        remove={removeWorkspaceLogo}
      />
      <NameForm
        id="workspaceName"
        label={m.nameLabel}
        initialValue={workspace.name}
        maxLength={100}
        required
        messages={{ required: m.nameRequired, tooLong: m.nameTooLong }}
        save={renameWorkspace}
      />
      <NameForm
        id="issueKey"
        label={m.issueKeyLabel}
        hint={m.issueKeyHint}
        initialValue={workspace.issueKey}
        maxLength={10}
        required
        messages={{ required: m.issueKeyInvalid, tooLong: m.issueKeyInvalid }}
        save={(value) => setIssueKey(value.toUpperCase())}
      />
    </FieldGroup>
  );
}
