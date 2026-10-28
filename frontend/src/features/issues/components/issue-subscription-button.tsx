"use client";

import { BellIcon, BellOffIcon, BellRingIcon, CornerDownRightIcon, ListTreeIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuRadioGroup,
  DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { messages } from "@/messages";

const m = messages.issues.activity;

export function IssueSubscriptionButton({ subscribed, includeDescendants, inherited, onChange }: {
  subscribed: boolean;
  includeDescendants: boolean;
  inherited: boolean;
  onChange: (subscribed: boolean, includeDescendants?: boolean) => void;
}) {
  const Icon = inherited ? CornerDownRightIcon : !subscribed ? BellIcon : includeDescendants ? ListTreeIcon : BellRingIcon;
  const label = inherited ? m.inheritedSubscription : subscribed ? m.subscribed : m.subscribe;
  const scope = inherited ? "inherited" : !subscribed ? "none" : includeDescendants ? "descendants" : "self";

  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger
        openOnHover
        delay={120}
        closeDelay={150}
        onClick={(event) => {
          if (inherited) {
            event.preventDefault();
            onChange(false);
          }
        }}
        render={<Button type="button" variant="ghost" size="sm" className="text-muted-foreground max-md:h-11" />}
      >
        <Icon data-icon="inline-start" />
        {label}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-44" aria-label={m.subscriptionScope}>
        <DropdownMenuRadioGroup value={scope} onValueChange={(value) => onChange(true, value === "descendants")}>
          <DropdownMenuRadioItem value="self"><BellRingIcon />{m.subscribeSelf}</DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="descendants"><ListTreeIcon />{m.subscribeDescendants}</DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
        {subscribed ? <>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => onChange(false)}><BellOffIcon />{m.unsubscribe}</DropdownMenuItem>
        </> : null}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
