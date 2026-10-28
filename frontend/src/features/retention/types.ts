export type RetentionUnit = "DAYS" | "WEEKS" | "MONTHS";

export type RetentionItem = "issues" | "documents" | "comments" | "rejectedRequests";

export interface RetentionPeriod {
  amount: number;
  unit: RetentionUnit;
}

/** How long deleted things are kept before they are removed for good. Null: never removed. */
export type RetentionSettings = Record<RetentionItem, RetentionPeriod | null>;
