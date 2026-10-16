"use client";

import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { ChevronsUpDownIcon, CircleHelpIcon, LogOutIcon, SettingsIcon, ShieldIcon } from "lucide-react";
import { personName } from "@/components/person";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { logout } from "@/features/auth/api";
import type { Me } from "@/features/auth/types";
import { ApiError } from "@/lib/api/errors";
import { initial } from "@/lib/initials";
import { messages } from "@/messages";

const m = messages.userMenu;

function isSessionGone(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

/**
 * Loads the sign-in page as a new document instead of navigating inside the app. Next.js keeps
 * visited screens alive with their state, so after an in-app move to /login and a new sign-in the
 * old screens came back as they were: this menu still "signed out" (its button disabled), and
 * the data cached for the previous person still in memory. A fresh document has none of that.
 */
function leave() {
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- a new document is the point
  window.location.assign("/login");
}

export function UserMenu({ user, placement }: { user: Me; placement: "sidebar" | "header" }) {
  const mutation = useMutation({
    mutationFn: logout,
    // Without this an offline browser parks the request, and the button stays disabled for good.
    networkMode: "always",
    onSuccess: leave,
    // The session had already ended (expired, or signed out in another tab): same place to go.
    onError: (error) => {
      if (isSessionGone(error)) leave();
    },
  });
  const inSidebar = placement === "sidebar";
  const name = personName(user);
  const isAdmin = user.role === "ADMIN";

  return (
    <div data-tour="user-menu" className="flex min-w-0 flex-col gap-1">
      {mutation.isError && !isSessionGone(mutation.error) ? (
        <p role="alert" className="px-2 text-[0.8125rem] leading-5 text-destructive">
          {m.logoutFailed}
        </p>
      ) : null}
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="ghost"
              aria-label={`${m.label}: ${name}`}
              className={
                inSidebar
                  ? "h-8 w-full justify-start px-2 text-[0.8125rem] hover:bg-sidebar-accent aria-expanded:bg-sidebar-accent"
                  : "h-9 max-w-48 px-2 text-[0.8125rem]"
              }
            />
          }
        >
          <Avatar size="sm">
            {user.avatarUrl ? <AvatarImage src={user.avatarUrl} alt="" /> : null}
            <AvatarFallback>{initial(name)}</AvatarFallback>
          </Avatar>
          <span className="truncate">{name}</span>
          <ChevronsUpDownIcon data-icon="inline-end" className="ml-auto text-muted-foreground" />
        </DropdownMenuTrigger>
        <DropdownMenuContent
          side={inSidebar ? "top" : "bottom"}
          align={inSidebar ? "start" : "end"}
          className={inSidebar ? undefined : "w-56"}
        >
          <DropdownMenuGroup>
            <DropdownMenuLabel className="flex flex-col gap-0.5">
              <span className="truncate text-sm text-foreground">{name}</span>
              <span className="truncate font-normal">
                {user.displayName ? `${user.loginId} · ` : ""}
                {isAdmin ? m.roleAdmin : m.roleMember}
              </span>
            </DropdownMenuLabel>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem render={<Link href="/settings" />}>
              <SettingsIcon />
              {m.settings}
            </DropdownMenuItem>
            {isAdmin ? (
              <DropdownMenuItem render={<Link href="/admin/workspace" />}>
                <ShieldIcon />
                {m.adminSettings}
              </DropdownMenuItem>
            ) : null}
            <DropdownMenuItem render={<Link href="/help" />}>
              <CircleHelpIcon />
              {m.help}
            </DropdownMenuItem>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem disabled={mutation.isPending} onClick={() => mutation.mutate()}>
              <LogOutIcon />
              {m.logout}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
