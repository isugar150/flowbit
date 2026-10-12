"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { ChevronsUpDownIcon, LogOutIcon, SettingsIcon, ShieldIcon } from "lucide-react";
import { useRouter } from "next/navigation";
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
import { initial } from "@/lib/initials";
import { messages } from "@/messages";

const m = messages.userMenu;

export function UserMenu({ user, placement }: { user: Me; placement: "sidebar" | "header" }) {
  const router = useRouter();
  const [signedOut, setSignedOut] = useState(false);
  const mutation = useMutation({
    mutationFn: logout,
    onSuccess: () => {
      setSignedOut(true);
      router.replace("/login");
      // Drop everything the router cached for the signed-in user.
      router.refresh();
    },
  });
  const inSidebar = placement === "sidebar";
  const name = personName(user);
  const isAdmin = user.role === "ADMIN";

  return (
    <div className="flex min-w-0 flex-col gap-1">
      {mutation.isError ? (
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
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuItem disabled={mutation.isPending || signedOut} onClick={() => mutation.mutate()}>
              <LogOutIcon />
              {m.logout}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
