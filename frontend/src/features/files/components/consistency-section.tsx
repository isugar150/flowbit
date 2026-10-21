"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { TimeAgo } from "@/components/dates";
import { FormAlert } from "@/features/auth/components/form-alert";
import { formatSize } from "@/features/issues/components/issue-attachments";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { cleanupFiles } from "../api";
import type { Consistency, MissingSample } from "../types";
import { Block, Cell, DataTable, Fact, Name } from "./files-parts";

const m = messages.files;
const c = m.check;

/** What a missing file was: its name and, for an attached file, where. */
function Place({ sample }: { sample: MissingSample }) {
  if (sample.kind === "IMAGE") return <>{c.kinds.IMAGE}</>;
  if (sample.issueKey) {
    return (
      <Link href={`/issue/${sample.issueKey}`} className="underline-offset-2 hover:underline">
        {sample.issueKey}
      </Link>
    );
  }
  return <>{sample.source ? m.largest.sources[sample.source] : "-"}</>;
}

/**
 * The storage held against the database: what nothing uses (and can be removed), what the database wants and the
 * storage does not have, and the button that removes the first (docs/features/admin-files.md).
 */
export function ConsistencySection({ data, onCleaned }: { data: Consistency; onCleaned: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const cleanup = useMutation({
    mutationFn: cleanupFiles,
    onSuccess: () => {
      setConfirming(false);
      onCleaned();
    },
  });
  const count = m.count;
  const { cleanable, orphans, missing } = data;
  const missingTotal = missing.files + missing.images;
  const orphanTotal = orphans.files.count + orphans.images.count + orphans.originals.count;
  const result = cleanup.data;

  return (
    <Block title={c.title} hint={c.description}>
      <ul className="flex flex-col divide-y rounded-xl border">
        <Fact
          label={c.cleanable}
          hint={c.cleanableHint(data.graceHours)}
          value={`${count(cleanable.count)} · ${formatSize(cleanable.bytes)}`}
        />
        <Fact label={c.inGrace} hint={c.inGraceHint(data.graceHours)} value={count(data.inGrace)} />
        <Fact
          label={c.missing}
          hint={c.missingHint}
          value={c.missingValue(count(missing.files), count(missing.images))}
          bad={missingTotal > 0}
        />
        <Fact label={c.withoutOriginal} hint={c.withoutOriginalHint} value={count(data.imagesWithoutOriginal)} />
        <Fact label={c.unknown} hint={c.unknownHint} value={count(data.unknownObjects)} />
      </ul>
      {orphanTotal > 0 ? (
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">
          {c.byArea(
            `${count(orphans.files.count)}(${formatSize(orphans.files.bytes)})`,
            `${count(orphans.images.count)}(${formatSize(orphans.images.bytes)})`,
            `${count(orphans.originals.count)}(${formatSize(orphans.originals.bytes)})`,
          )}
        </p>
      ) : null}

      {cleanup.isError ? <FormAlert>{apiErrorMessage(cleanup.error)}</FormAlert> : null}
      {confirming ? (
        <p role="status" className="rounded-lg border px-3 py-2 text-[0.8125rem] leading-5">
          {m.cleanup.confirm(count(cleanable.count), formatSize(cleanable.bytes))}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center gap-3">
        {cleanable.count > 0 ? (
          confirming ? (
            <>
              <Button type="button" variant="destructive" className="h-9 max-md:h-11" disabled={cleanup.isPending} onClick={() => cleanup.mutate()}>
                {cleanup.isPending ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
                {cleanup.isPending ? m.cleanup.working : m.cleanup.confirmButton}
              </Button>
              {cleanup.isPending ? null : (
                <Button type="button" variant="outline" className="h-9 max-md:h-11" onClick={() => setConfirming(false)}>
                  {m.cleanup.cancel}
                </Button>
              )}
            </>
          ) : (
            <Button type="button" variant="outline" className="h-9 max-md:h-11" onClick={() => setConfirming(true)}>
              {m.cleanup.button}
            </Button>
          )
        ) : (
          <span className="text-[0.8125rem] leading-5 text-muted-foreground">{m.cleanup.nothing}</span>
        )}
        {result ? (
          <span role="status" className="text-[0.8125rem] leading-5">
            {m.cleanup.done(count(result.deleted.total.count), formatSize(result.bytes))}
            {result.failed > 0 ? ` ${m.cleanup.failed(count(result.failed))}` : ""}
          </span>
        ) : null}
      </div>

      {data.orphanSamples.length > 0 ? (
        <Block
          title={c.orphanSamples}
          hint={orphanTotal > data.orphanSamples.length ? c.onlyShown(data.orphanSamples.length, orphanTotal) : undefined}
        >
          <DataTable
            caption={c.orphanSamples}
            head={[{ label: c.area }, { label: c.name }, { label: c.size, right: true }, { label: c.modified }, { label: c.state }]}
          >
            {data.orphanSamples.map((sample) => (
              <tr key={`${sample.area}/${sample.key}`}>
                <Cell>{c.areas[sample.area]}</Cell>
                <Cell className="font-mono text-xs">
                  <Name>{sample.key}</Name>
                </Cell>
                <Cell right>{formatSize(sample.bytes)}</Cell>
                <Cell className="whitespace-nowrap">
                  <TimeAgo iso={sample.modifiedAt} />
                </Cell>
                <Cell className="whitespace-nowrap">{sample.cleanable ? c.stateReady : c.stateWaiting}</Cell>
              </tr>
            ))}
          </DataTable>
        </Block>
      ) : null}

      {data.missingSamples.length > 0 ? (
        <Block
          title={c.missingSamples}
          hint={missingTotal > data.missingSamples.length ? c.onlyShown(data.missingSamples.length, missingTotal) : undefined}
        >
          <DataTable caption={c.missingSamples} head={[{ label: c.area }, { label: c.name }, { label: c.place }]}>
            {data.missingSamples.map((sample) => (
              <tr key={`${sample.kind}/${sample.key}`}>
                <Cell>{c.kinds[sample.kind]}</Cell>
                <Cell>
                  <Name>{sample.name ?? sample.key}</Name>
                </Cell>
                <Cell className="whitespace-nowrap">
                  <Place sample={sample} />
                </Cell>
              </tr>
            ))}
          </DataTable>
        </Block>
      ) : null}
    </Block>
  );
}
