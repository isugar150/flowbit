import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { cn } from "@/lib/utils";
import { Textarea } from "@/components/ui/textarea";

const WIDE = "sm:col-span-2";

/** One labelled text input of a settings form, with the server's message for it when there is one. */
export function TextSetting({
  id,
  label,
  hint,
  value,
  error,
  type = "text",
  placeholder,
  readOnly,
  onChange,
  wide,
}: {
  id: string;
  label: string;
  hint?: string;
  value: string;
  error?: string;
  type?: "text" | "password" | "url";
  placeholder?: string;
  readOnly?: boolean;
  onChange?: (value: string) => void;
  /** Takes the whole row of a two-column FieldGrid. */
  wide?: boolean;
}) {
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

  return (
    <Field
      data-invalid={error ? true : undefined}
      className={wide ? WIDE : undefined}
    >
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input
        id={id}
        type={type}
        value={value}
        placeholder={placeholder}
        readOnly={readOnly}
        className="h-9 max-md:h-11"
        // Nothing here is the administrator's own sign-in; keep password managers out of it.
        autoComplete={type === "password" ? "new-password" : "off"}
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        onChange={(event) => onChange?.(event.target.value)}
        onFocus={readOnly ? (event) => event.target.select() : undefined}
      />
      {error ? (
        <FieldError id={`${id}-error`}>{error}</FieldError>
      ) : hint ? (
        <FieldDescription id={`${id}-hint`}>{hint}</FieldDescription>
      ) : null}
    </Field>
  );
}

/** One on/off setting. */
export function CheckSetting({
  id,
  label,
  hint,
  checked,
  error,
  onChange,
  wide,
}: {
  id: string;
  label: string;
  hint?: string;
  checked: boolean;
  error?: string;
  onChange: (checked: boolean) => void;
  /** Takes the whole row of a two-column FieldGrid. */
  wide?: boolean;
}) {
  return (
    <div className={cn("flex flex-col gap-1", wide && WIDE)}>
      <label
        htmlFor={id}
        className="flex min-h-6 items-center gap-2 font-medium max-md:min-h-11"
      >
        <input
          id={id}
          type="checkbox"
          className="size-4 accent-primary"
          checked={checked}
          aria-describedby={error || hint ? `${id}-hint` : undefined}
          onChange={(event) => onChange(event.target.checked)}
        />
        {label}
      </label>
      {error ? (
        <FieldError id={`${id}-hint`}>{error}</FieldError>
      ) : hint ? (
        <FieldDescription id={`${id}-hint`}>{hint}</FieldDescription>
      ) : null}
    </div>
  );
}

/** A labelled multi-line input: templates, key files, lists with one entry per line. */
export function TextAreaSetting({
  id,
  label,
  hint,
  value,
  error,
  placeholder,
  rows = 4,
  onChange,
  wide,
}: {
  id: string;
  label: string;
  hint?: string;
  value: string;
  error?: string;
  placeholder?: string;
  rows?: number;
  onChange: (value: string) => void;
  /** Takes the whole row of a two-column FieldGrid. */
  wide?: boolean;
}) {
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

  return (
    <Field
      data-invalid={error ? true : undefined}
      className={wide ? WIDE : undefined}
    >
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Textarea
        id={id}
        rows={rows}
        value={value}
        placeholder={placeholder}
        className="font-mono text-[0.8125rem] leading-5"
        autoComplete="off"
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        onChange={(event) => onChange(event.target.value)}
      />
      {error ? (
        <FieldError id={`${id}-error`}>{error}</FieldError>
      ) : hint ? (
        <FieldDescription id={`${id}-hint`}>{hint}</FieldDescription>
      ) : null}
    </Field>
  );
}

/** A labelled choice between a few fixed values. */
export function SelectSetting<T extends string>({
  id,
  label,
  hint,
  value,
  options,
  wide,
  onChange,
}: {
  id: string;
  label: string;
  hint?: string;
  value: T;
  options: { value: T; label: string }[];
  wide?: boolean;
  onChange: (value: T) => void;
}) {
  return (
    <Field className={wide ? WIDE : undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <NativeSelect
        id={id}
        className="h-9 w-full max-md:h-11"
        value={value}
        aria-describedby={hint ? `${id}-hint` : undefined}
        onChange={(event) => onChange(event.target.value as T)}
      >
        {options.map((option) => (
          <NativeSelectOption key={option.value} value={option.value}>
            {option.label}
          </NativeSelectOption>
        ))}
      </NativeSelect>
      {hint ? (
        <FieldDescription id={`${id}-hint`}>{hint}</FieldDescription>
      ) : null}
    </Field>
  );
}

/**
 * A named group of related settings inside one form: "연결", "사용자 찾기". Short fields sit two
 * to a row on wide screens; a field marked `wide` takes the whole row.
 */
export function FieldSection({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-col gap-0.5">
        <h4 className="text-[0.8125rem] leading-5 font-semibold">{title}</h4>
        {description ? (
          <p className="text-[0.8125rem] leading-5 text-muted-foreground">
            {description}
          </p>
        ) : null}
      </div>
      <div className="grid gap-x-4 gap-y-5 sm:grid-cols-2">{children}</div>
    </section>
  );
}

/** Settings most installations never touch, folded away until asked for. */
export function AdvancedSettings({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <details className="group rounded-lg border">
      <summary className="flex min-h-9 cursor-pointer list-none items-center gap-2 rounded-lg px-3 text-[0.8125rem] font-medium text-muted-foreground outline-none select-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 max-md:min-h-11 [&::-webkit-details-marker]:hidden">
        <span
          aria-hidden="true"
          className="transition-transform group-open:rotate-90"
        >
          ›
        </span>
        {label}
      </summary>
      <div className="flex flex-col gap-6 border-t px-3 py-4">{children}</div>
    </details>
  );
}

/** A choice between a few modes that changes which fields follow, shown as joined buttons. */
export function SegmentedSetting<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T;
  options: { value: T; label: string; description?: string }[];
  onChange: (value: T) => void;
}) {
  return (
    <fieldset className="flex flex-col gap-2 sm:col-span-2">
      <legend className="mb-2 text-sm font-medium">{label}</legend>
      <div className="grid gap-2 sm:grid-cols-2">
        {options.map((option) => {
          const selected = option.value === value;
          return (
            <label
              key={option.value}
              className={cn(
                "flex cursor-pointer flex-col gap-0.5 rounded-lg border px-3 py-2 has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
                selected ? "border-primary bg-primary/5" : "hover:bg-muted",
              )}
            >
              <span className="flex items-center gap-2 font-medium">
                <input
                  type="radio"
                  className="size-4 accent-primary"
                  checked={selected}
                  onChange={() => onChange(option.value)}
                />
                {option.label}
              </span>
              {option.description ? (
                <span className="pl-6 text-[0.8125rem] leading-5 text-muted-foreground">
                  {option.description}
                </span>
              ) : null}
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
