"use client";

import { useState } from "react";
import dynamic from "next/dynamic";
import { PlusIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { messages } from "@/messages";

const m = messages.projects;

// The button is in the sidebar of every page; the form behind it (and the form and validation
// libraries it brings) is fetched when the dialog is first opened, or just before, when the
// button is pointed at.
const loadForm = () => import("./create-project-form");
const preloadForm = () => void loadForm();
const CreateProjectForm = dynamic(() => loadForm().then((module) => module.CreateProjectForm), {
  ssr: false,
  loading: () => (
    <div className="flex flex-col gap-5">
      <DialogTitle>{m.newTitle}</DialogTitle>
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-20 w-full" />
    </div>
  ),
});

/**
 * A new project needs only a name.
 * `variant="icon"` is the small plus in the sidebar; `"button"` is the labelled button on pages;
 * `"ghost"` is the quiet labelled button in a page header.
 */
export function CreateProjectDialog({ variant }: { variant: "icon" | "button" | "ghost" }) {
  const [open, setOpen] = useState(false);
  const preload = { onPointerEnter: preloadForm, onFocus: preloadForm };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      {variant === "icon" ? (
        <DialogTrigger render={<Button variant="ghost" size="icon-sm" aria-label={m.new} {...preload} />}>
          <PlusIcon />
        </DialogTrigger>
      ) : variant === "ghost" ? (
        <DialogTrigger render={<Button variant="ghost" size="sm" className="text-muted-foreground max-md:h-11" {...preload} />}>
          <PlusIcon data-icon="inline-start" />
          {m.new}
        </DialogTrigger>
      ) : (
        <DialogTrigger render={<Button className="max-md:h-11" {...preload} />}>
          <PlusIcon data-icon="inline-start" />
          {m.new}
        </DialogTrigger>
      )}
      <DialogContent initialFocus={() => document.getElementById("project-name")}>
        <CreateProjectForm onClose={() => setOpen(false)} />
      </DialogContent>
    </Dialog>
  );
}
