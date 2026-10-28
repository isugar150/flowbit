export type ActionType = "NOTIFY" | "ASSIGN" | "ADD_LABELS" | "REMOVE_LABELS";
export type Audience = "PEOPLE" | "ASSIGNEES" | "CREATOR" | "MANAGERS" | "PROJECT_FIELD";
export type AutomationAction = { id: string; type: ActionType; audience: Audience; userIds: string[]; labelIds: string[]; message: string; fieldId?: string | null };
export type AutomationInput = { name: string; enabled: boolean; fromStatusId: string | null; toStatusId: string; actions: AutomationAction[] };
export type AutomationRule = AutomationInput & { id: string; projectId: string; createdAt: string };
export type AutomationRun = { id: string; ruleName: string; issueNumber: number; issueKey: string; issueTitle: string; results: { type: ActionType; message: string }[]; createdAt: string };
