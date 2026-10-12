"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { ChevronsUpDownIcon, LogOutIcon } from "lucide-react";
import { useRouter } from "next/navigation";
import { useTheme } from "next-themes";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { logout } from "@/features/auth/api";
import type { Me } from "@/features/auth/types";
import { messages } from "@/messages";

const m = messages.userMenu;

export function UserMenu({ user, placement }: { user: Me; placement: "sidebar" | "header" }) {
  const router = useRouter();
  const { theme, setTheme } = useTheme();
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

  return (
    <div className="flex flex-col gap-1">
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
              aria-label={`${m.label}: ${user.loginId}`}
              className={inSidebar ? "w-full justify-between" : "max-w-48"}
            />
          }
        >
          <span className="truncate">{user.loginId}</span>
          <ChevronsUpDownIcon data-icon="inline-end" className="text-muted-foreground" />
        </DropdownMenuTrigger>
        <DropdownMenuContent
          side={inSidebar ? "top" : "bottom"}
          align={inSidebar ? "start" : "end"}
          className={inSidebar ? undefined : "w-56"}
        >
          <DropdownMenuGroup>
            <DropdownMenuLabel className="flex flex-col gap-0.5">
              <span className="truncate text-sm text-foreground">{user.loginId}</span>
              <span className="font-normal">{user.role === "ADMIN" ? m.roleAdmin : m.roleMember}</span>
            </DropdownMenuLabel>
          </DropdownMenuGroup>
          <DropdownMenuSeparator />
          <DropdownMenuGroup>
            <DropdownMenuLabel>{m.theme}</DropdownMenuLabel>
            <DropdownMenuRadioGroup value={theme ?? "light"} onValueChange={(value) => setTheme(String(value))}>
              <DropdownMenuRadioItem value="light">{m.themeLight}</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="dark">{m.themeDark}</DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="system">{m.themeSystem}</DropdownMenuRadioItem>
            </DropdownMenuRadioGroup>
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
