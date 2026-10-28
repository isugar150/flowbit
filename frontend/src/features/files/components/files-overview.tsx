"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { TimeAgo } from "@/components/dates";
import { FormAlert } from "@/features/auth/components/form-alert";
import { formatSize } from "@/lib/format-size";
import { formatLimit } from "@/features/settings/upload-limit";
import { apiErrorMessage } from "@/lib/api/error-message";
import { messages } from "@/messages";
import { fetchFiles } from "../api";
import type { FileUses, StorageInspection, Tally } from "../types";
import { ConsistencySection } from "./consistency-section";
import { Block, Cell, DataTable, Name, Stat } from "./files-parts";

const m = messages.files;

const USE_ROWS: (keyof Omit<FileUses, "awaitingPurge">)[] = [
  "ticketAttachments",
  "ticketCommentFiles",
  "contentCommentFiles",
  "pendingFiles",
  "avatars",
  "logo",
  "projectIcons",
  "contentImages",
];

function Overview({ data, onCleaned }: { data: StorageInspection; onCleaned: () => void }) {
  const { storage, areas, uses } = data;
  const figure = (tally: Tally) => ({ size: formatSize(tally.bytes), count: m.count(tally.count) });

  return (
    <div className="flex flex-col gap-8">
      <Block title={m.storage.title}>
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5 rounded-xl border px-4 py-3 text-[0.8125rem] leading-5">
          <dt className="text-muted-foreground">{m.storage.type}</dt>
          <dd>{m.storage.types[storage.type]}</dd>
          <dt className="text-muted-foreground">{m.storage.location}</dt>
          <dd className="min-w-0 font-mono text-xs leading-5 break-all">{storage.location}</dd>
          {storage.region ? (
            <>
              <dt className="text-muted-foreground">{m.storage.region}</dt>
              <dd>{storage.region}</dd>
            </>
          ) : null}
          {storage.endpoint ? (
            <>
              <dt className="text-muted-foreground">{m.storage.endpoint}</dt>
              <dd className="min-w-0 font-mono text-xs leading-5 break-all">{storage.endpoint}</dd>
            </>
          ) : null}
          <dt className="text-muted-foreground">{m.storage.maxUpload}</dt>
          <dd>
            {formatLimit(data.maxUploadBytes)}
            <span className="block text-xs leading-4 text-muted-foreground">{m.storage.maxUploadHint}</span>
          </dd>
        </dl>
      </Block>

      <Block title={m.usage.title}>
        <div className="grid grid-cols-2 gap-3 max-narrow:grid-cols-1 lg:grid-cols-4">
          <Stat label={m.usage.total} {...figure(data.total)} />
          <Stat label={m.usage.files} hint={m.usage.filesHint} {...figure(areas.files)} />
          <Stat label={m.usage.images} hint={m.usage.imagesHint} {...figure(areas.images)} />
          <Stat label={m.usage.originals} hint={m.usage.originalsHint} {...figure(areas.originals)} />
        </div>
      </Block>

      <Block title={m.uses.title} hint={m.uses.imagesHint}>
        <DataTable
          caption={m.uses.title}
          head={[{ label: m.uses.kind }, { label: m.uses.count, right: true }, { label: m.uses.size, right: true }]}
        >
          {USE_ROWS.map((row) => (
            <tr key={row}>
              <Cell>
                {m.uses[row]}
                {row === "pendingFiles" ? <span className="block text-xs leading-4 text-muted-foreground">{m.uses.pendingHint}</span> : null}
              </Cell>
              <Cell right>{m.count(uses[row].count)}</Cell>
              <Cell right>{formatSize(uses[row].bytes)}</Cell>
            </tr>
          ))}
        </DataTable>
        {uses.awaitingPurge.count > 0 ? (
          <p className="text-[0.8125rem] leading-5 text-muted-foreground">
            {m.uses.awaitingPurge(m.count(uses.awaitingPurge.count), formatSize(uses.awaitingPurge.bytes))}{" "}
            <Link href="/admin/retention" className="underline underline-offset-2 hover:text-foreground">
              {m.uses.retentionLink}
            </Link>
          </p>
        ) : null}
      </Block>

      <Block title={m.uploaders.title} hint={m.uploaders.hint}>
        {data.topUploaders.length === 0 ? (
          <p className="rounded-xl border px-4 py-3 text-[0.8125rem] leading-5 text-muted-foreground">{m.uploaders.empty}</p>
        ) : (
          <DataTable
            caption={m.uploaders.title}
            head={[{ label: m.uploaders.person }, { label: m.uses.count, right: true }, { label: m.uses.size, right: true }]}
          >
            {data.topUploaders.map((uploader) => (
              <tr key={uploader.userId}>
                <Cell>
                  <Name>{uploader.name}</Name>
                </Cell>
                <Cell right>{m.count(uploader.count)}</Cell>
                <Cell right>{formatSize(uploader.bytes)}</Cell>
              </tr>
            ))}
          </DataTable>
        )}
      </Block>

      <Block title={m.largest.title} hint={m.largest.hint}>
        {data.largestFiles.length === 0 ? (
          <p className="rounded-xl border px-4 py-3 text-[0.8125rem] leading-5 text-muted-foreground">{m.largest.empty}</p>
        ) : (
          <DataTable
            caption={m.largest.title}
            head={[
              { label: m.largest.file },
              { label: m.largest.size, right: true },
              { label: m.largest.uploader },
              { label: m.largest.place },
              { label: m.largest.uploaded },
            ]}
          >
            {data.largestFiles.map((file, index) => (
              <tr key={`${file.name}-${index}`}>
                <Cell>
                  <Name>{file.name}</Name>
                </Cell>
                <Cell right>{formatSize(file.bytes)}</Cell>
                <Cell className="whitespace-nowrap">{file.uploader}</Cell>
                <Cell className="whitespace-nowrap">
                  {file.issueKey ? (
                    <Link href={`/issue/${file.issueKey}`} className="underline-offset-2 hover:underline">
                      {file.issueKey}
                    </Link>
                  ) : (
                    m.largest.sources[file.source]
                  )}
                </Cell>
                <Cell className="whitespace-nowrap">
                  <TimeAgo iso={file.createdAt} />
                </Cell>
              </tr>
            ))}
          </DataTable>
        )}
      </Block>

      <ConsistencySection data={data.consistency} onCleaned={onCleaned} />
    </div>
  );
}

/**
 * The administrator's file page: where the files are, how much there is of what, and the storage held against the
 * database. Read in the browser each time the page is opened (and by "다시 점검"): it lists the whole storage.
 */
export function FilesOverview() {
  const query = useQuery({ queryKey: ["admin-files"], queryFn: fetchFiles, staleTime: 0, gcTime: 0, refetchOnWindowFocus: false, retry: false });

  if (query.isPending) {
    return (
      <div className="flex flex-col gap-4" role="status">
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">{m.loading}</p>
        <div className="grid grid-cols-2 gap-3 max-narrow:grid-cols-1 lg:grid-cols-4" aria-hidden="true">
          {[0, 1, 2, 3].map((index) => (
            <Skeleton key={index} className="h-24 rounded-xl" />
          ))}
        </div>
        <Skeleton className="h-48 rounded-xl" aria-hidden="true" />
      </div>
    );
  }
  if (query.isError) {
    return (
      <div className="flex flex-col items-start gap-3">
        <FormAlert>{apiErrorMessage(query.error)}</FormAlert>
        <Button type="button" variant="outline" className="h-9 max-md:h-11" onClick={() => void query.refetch()}>
          {m.retry}
        </Button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-[0.8125rem] leading-5 text-muted-foreground">
          {m.checkedAt}: <TimeAgo iso={query.data.checkedAt} />
        </p>
        <Button type="button" variant="outline" className="h-9 max-md:h-11" disabled={query.isFetching} onClick={() => void query.refetch()}>
          {query.isFetching ? <Spinner data-icon="inline-start" aria-hidden="true" /> : null}
          {query.isFetching ? m.rechecking : m.recheck}
        </Button>
      </div>
      <Overview data={query.data} onCleaned={() => void query.refetch()} />
    </div>
  );
}
