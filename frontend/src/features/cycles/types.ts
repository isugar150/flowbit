/** A project's repeating work period (docs/features/cycles.md). Both days belong to it. */
export type Cycle = {
  id: string;
  number: number;
  /** YYYY-MM-DD */
  startsOn: string;
  endsOn: string;
  /** The issues in the cycle, canceled ones not counted, and how many of them are done. */
  total: number;
  done: number;
};

export type CycleSettings = {
  enabled: boolean;
  /** How many weeks one cycle lasts: 1 to 4. */
  weeks: number;
  /** The weekday a cycle starts on, ISO: 1 = Monday … 7 = Sunday. */
  startDay: number;
};

export type ProjectCycles = {
  settings: CycleSettings;
  /** Today in the workspace's time zone. */
  today: string;
  current: Cycle | null;
  upcoming: Cycle | null;
  /** The latest finished ones, newest first. */
  past: Cycle[];
};
