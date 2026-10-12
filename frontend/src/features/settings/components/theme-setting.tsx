"use client";

import { useSyncExternalStore } from "react";
import { useTheme } from "next-themes";
import { Field, FieldLabel, FieldLegend, FieldSet } from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { messages } from "@/messages";

const m = messages.settings.appearance;
const OPTIONS = [
  { value: "light", label: m.light },
  { value: "dark", label: m.dark },
  { value: "system", label: m.system },
] as const;

const subscribe = () => () => {};

/**
 * Light, dark, or follow the device. Kept in this browser only (next-themes stores it in
 * localStorage), so each device can have its own choice.
 */
export function ThemeSetting() {
  const { theme, setTheme } = useTheme();
  // The stored choice is only known in the browser; until then no option is shown as selected,
  // which avoids a mismatch with the server-rendered markup.
  const hydrated = useSyncExternalStore(
    subscribe,
    () => true,
    () => false,
  );

  return (
    <FieldSet>
      <FieldLegend variant="label">{m.themeLabel}</FieldLegend>
      <RadioGroup value={hydrated ? (theme ?? "light") : null} onValueChange={(value) => setTheme(String(value))}>
        {OPTIONS.map((option) => (
          <Field key={option.value} orientation="horizontal">
            <RadioGroupItem id={`theme-${option.value}`} value={option.value} />
            <FieldLabel htmlFor={`theme-${option.value}`} className="font-normal">
              {option.label}
            </FieldLabel>
          </Field>
        ))}
      </RadioGroup>
    </FieldSet>
  );
}
