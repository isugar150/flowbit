"use client";

import { BoxIcon } from "lucide-react";
import { ImageUploadField } from "@/features/settings/components/image-upload-field";
import { messages } from "@/messages";
import { useProjectApi } from "../project-api-scope";

const m = messages.projects;

/** Upload or remove the project's icon (managers only); shown in the sidebar, lists and the overview. */
export function ProjectIconField({ projectId, iconUrl }: { projectId: string; iconUrl: string | null }) {
  const { uploadProjectIcon, removeProjectIcon } = useProjectApi();
  return (
    <ImageUploadField
      id="project-icon"
      label={m.iconLabel}
      imageUrl={iconUrl}
      imageAlt={m.iconAlt}
      fallback={<BoxIcon className="size-6" aria-hidden="true" />}
      shape="square"
      upload={(file) => uploadProjectIcon(projectId, file)}
      remove={() => removeProjectIcon(projectId)}
    />
  );
}
