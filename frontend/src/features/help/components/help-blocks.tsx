import { Fragment } from "react";
import { InfoIcon } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { cn } from "@/lib/utils";
import type { HelpBlock } from "@/features/help/content";
import { messages } from "@/messages";
import { HelpText, KeyCombo } from "./help-text";

const m = messages.help;

/** One block of a help section, by kind (features/help/content.ts). */
export function HelpBlocks({ blocks }: { blocks: HelpBlock[] }) {
  return blocks.map((block, index) => <Block key={index} block={block} />);
}

function Block({ block }: { block: HelpBlock }) {
  switch (block.kind) {
    case "p":
      return (
        <p>
          <HelpText text={block.text} />
        </p>
      );
    case "list":
      return (
        <ul className="flex flex-col gap-2">
          {block.items.map((item, index) => (
            <li
              key={index}
              className="relative pl-4 before:absolute before:top-[0.5625rem] before:left-1 before:size-1 before:rounded-full before:bg-muted-foreground before:content-['']"
            >
              <HelpText text={item} />
            </li>
          ))}
        </ul>
      );
    case "steps":
      return (
        <ol className="flex flex-col gap-3">
          {block.items.map((item, index) => (
            <li key={index} className="flex gap-3">
              <span
                className="flex size-5.5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-medium tabular-nums"
                aria-hidden="true"
              >
                {index + 1}
              </span>
              <span className="min-w-0">
                <HelpText text={item} />
              </span>
            </li>
          ))}
        </ol>
      );
    case "note":
      return (
        <Alert>
          <InfoIcon />
          <AlertDescription>
            <HelpText text={block.text} />
          </AlertDescription>
        </Alert>
      );
    case "table":
      return (
        <HelpTable head={block.head}>
          {block.rows.map(([name, ...values], index) => (
            <tr key={index} className="border-t">
              <th scope="row" className="px-3 py-2 text-left align-top font-medium">
                <HelpText text={name} />
              </th>
              {values.map((value, column) => (
                <td key={column} className="px-3 py-2 align-top text-muted-foreground">
                  <HelpText text={value} />
                </td>
              ))}
            </tr>
          ))}
        </HelpTable>
      );
    case "keys":
      return (
        <HelpTable head={m.keysHead}>
          {block.rows.map((row, index) => (
            <tr key={index} className="border-t">
              <th scope="row" className="px-3 py-2 text-left align-top font-normal">
                <span className="flex flex-wrap items-center gap-1.5">
                  {row.keys.map((combo, at) => (
                    <Fragment key={combo}>
                      {at > 0 ? <span className="text-xs text-muted-foreground">{m.keysOr}</span> : null}
                      <KeyCombo combo={combo} />
                    </Fragment>
                  ))}
                </span>
              </th>
              <td className="px-3 py-2 align-top text-muted-foreground">{row.action}</td>
            </tr>
          ))}
        </HelpTable>
      );
    case "sub":
      return (
        <div className="flex flex-col gap-3 pt-2">
          <h4 className="font-semibold">{block.title}</h4>
          <HelpBlocks blocks={block.blocks} />
        </div>
      );
  }
}

function HelpTable({ head, children }: { head: readonly string[]; children: React.ReactNode }) {
  // Two columns are a name and its meaning: the name gets a fixed share. Wider tables size by content.
  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full border-collapse text-[0.8125rem] leading-5">
        <thead className="bg-muted/60">
          <tr>
            {head.map((title, column) => (
              <th
                key={title}
                scope="col"
                className={cn(
                  "px-3 py-2 text-left text-xs font-medium text-muted-foreground",
                  head.length === 2 && column === 0 && "w-2/5",
                  head.length > 2 && "min-w-28",
                )}
              >
                {title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
