import { Fragment } from "react";
import { InfoIcon } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
    case "steps": {
      const List = block.kind === "list" ? "ul" : "ol";
      return (
        <List
          className={
            block.kind === "list"
              ? "flex list-disc flex-col gap-1.5 pl-5 marker:text-muted-foreground"
              : "flex list-decimal flex-col gap-1.5 pl-5 marker:text-muted-foreground"
          }
        >
          {block.items.map((item, index) => (
            <li key={index} className="pl-1">
              <HelpText text={item} />
            </li>
          ))}
        </List>
      );
    }
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
          {block.rows.map(([name, value], index) => (
            <tr key={index} className="border-t">
              <th scope="row" className="px-3 py-2 text-left align-top font-medium">
                <HelpText text={name} />
              </th>
              <td className="px-3 py-2 align-top text-muted-foreground">
                <HelpText text={value} />
              </td>
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
        <div className="flex flex-col gap-3">
          <h4 className="font-semibold">{block.title}</h4>
          <HelpBlocks blocks={block.blocks} />
        </div>
      );
  }
}

function HelpTable({ head, children }: { head: readonly [string, string]; children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full border-collapse text-[0.8125rem] leading-5">
        <thead className="bg-muted">
          <tr>
            <th scope="col" className="w-2/5 px-3 py-2 text-left font-medium text-muted-foreground">
              {head[0]}
            </th>
            <th scope="col" className="px-3 py-2 text-left font-medium text-muted-foreground">
              {head[1]}
            </th>
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
