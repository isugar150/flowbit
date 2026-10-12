"use client"

import * as React from "react"
import {
  DayPicker,
  getDefaultClassNames,
  type DayButton,
} from "react-day-picker"
import { ko } from "react-day-picker/locale"
import { cn } from "cn"
import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react"

/**
 * A month calendar (react-day-picker) in the look of shadcn's Calendar. Korean by default:
 * "2026년 10월", weeks start on Sunday. Supports `mode="single"` and `mode="range"`.
 */
function Calendar({
  className,
  classNames,
  showOutsideDays = true,
  locale = ko,
  components,
  ...props
}: React.ComponentProps<typeof DayPicker>) {
  const defaults = getDefaultClassNames()

  return (
    <DayPicker
      locale={locale}
      showOutsideDays={showOutsideDays}
      className={cn("p-1 [--cell-size:--spacing(8)]", className)}
      classNames={{
        root: cn("w-fit", defaults.root),
        months: cn("relative flex flex-col gap-4 md:flex-row", defaults.months),
        month: cn("flex w-full flex-col gap-3", defaults.month),
        nav: cn(
          "absolute inset-x-0 top-0 flex w-full items-center justify-between",
          defaults.nav
        ),
        button_previous: cn(navButton, defaults.button_previous),
        button_next: cn(navButton, defaults.button_next),
        month_caption: cn(
          "flex h-(--cell-size) w-full items-center justify-center px-(--cell-size)",
          defaults.month_caption
        ),
        caption_label: cn(
          "text-[0.8125rem] leading-5 font-medium select-none",
          defaults.caption_label
        ),
        month_grid: "w-full border-collapse",
        weekdays: cn("flex", defaults.weekdays),
        weekday: cn(
          "flex-1 rounded-md text-[0.75rem] font-normal text-muted-foreground select-none",
          defaults.weekday
        ),
        week: cn("mt-1 flex w-full", defaults.week),
        day: cn(
          "group/day relative aspect-square h-full w-full p-0 text-center select-none [&:first-child[data-selected=true]_button]:rounded-l-md [&:last-child[data-selected=true]_button]:rounded-r-md",
          defaults.day
        ),
        range_start: cn("rounded-l-md bg-accent", defaults.range_start),
        range_middle: cn("rounded-none", defaults.range_middle),
        range_end: cn("rounded-r-md bg-accent", defaults.range_end),
        today: cn(
          "rounded-md bg-muted text-foreground data-[selected=true]:rounded-none",
          defaults.today
        ),
        outside: cn(
          "text-muted-foreground aria-selected:text-muted-foreground",
          defaults.outside
        ),
        disabled: cn("text-muted-foreground opacity-50", defaults.disabled),
        hidden: cn("invisible", defaults.hidden),
        ...classNames,
      }}
      components={{
        Chevron: ({ orientation, className: chevronClass, ...chevronProps }) =>
          orientation === "left" ? (
            <ChevronLeftIcon className={cn("size-4", chevronClass)} {...chevronProps} />
          ) : (
            <ChevronRightIcon className={cn("size-4", chevronClass)} {...chevronProps} />
          ),
        DayButton: CalendarDayButton,
        ...components,
      }}
      {...props}
    />
  )
}

const navButton =
  "inline-flex size-(--cell-size) items-center justify-center rounded-md text-muted-foreground outline-none select-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 aria-disabled:opacity-50"

function CalendarDayButton({
  className,
  day,
  modifiers,
  ...props
}: React.ComponentProps<typeof DayButton>) {
  const ref = React.useRef<HTMLButtonElement>(null)
  React.useEffect(() => {
    if (modifiers.focused) ref.current?.focus()
  }, [modifiers.focused])

  return (
    <button
      ref={ref}
      type="button"
      data-day={day.date.toLocaleDateString()}
      data-selected-single={
        modifiers.selected &&
        !modifiers.range_start &&
        !modifiers.range_end &&
        !modifiers.range_middle
      }
      data-range-start={modifiers.range_start}
      data-range-end={modifiers.range_end}
      data-range-middle={modifiers.range_middle}
      className={cn(
        "flex aspect-square size-auto w-full min-w-(--cell-size) items-center justify-center rounded-md text-[0.8125rem] leading-none font-normal tabular-nums outline-none hover:bg-muted focus-visible:relative focus-visible:z-10 focus-visible:ring-3 focus-visible:ring-ring/50",
        "data-[selected-single=true]:bg-primary data-[selected-single=true]:text-primary-foreground",
        "data-[range-middle=true]:rounded-none data-[range-middle=true]:bg-accent data-[range-middle=true]:text-accent-foreground",
        "data-[range-start=true]:rounded-md data-[range-start=true]:bg-primary data-[range-start=true]:text-primary-foreground",
        "data-[range-end=true]:rounded-md data-[range-end=true]:bg-primary data-[range-end=true]:text-primary-foreground",
        defaultDayClass,
        className
      )}
      {...props}
    />
  )
}

const defaultDayClass = getDefaultClassNames().day_button

export { Calendar, CalendarDayButton }
