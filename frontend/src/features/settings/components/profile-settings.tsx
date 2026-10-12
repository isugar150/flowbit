"use client";

import { FieldGroup } from "@/components/ui/field";
import type { Me } from "@/features/auth/types";
import { initials } from "@/lib/initials";
import { messages } from "@/messages";
import { removeAvatar, updateDisplayName, uploadAvatar } from "../api";
import { ImageUploadField } from "./image-upload-field";
import { NameForm } from "./name-form";

const m = messages.settings.profile;

export function ProfileSettings({ user }: { user: Me }) {
  return (
    <FieldGroup>
      <ImageUploadField
        id="avatar"
        label={m.imageLabel}
        imageUrl={user.avatarUrl}
        imageAlt={m.imageAlt}
        fallback={initials(user.displayName ?? user.loginId)}
        shape="circle"
        upload={uploadAvatar}
        remove={removeAvatar}
      />
      <NameForm
        id="displayName"
        label={m.nameLabel}
        hint={m.nameHint}
        initialValue={user.displayName ?? ""}
        maxLength={50}
        required={false}
        messages={{ tooLong: m.nameTooLong }}
        save={updateDisplayName}
      />
    </FieldGroup>
  );
}
