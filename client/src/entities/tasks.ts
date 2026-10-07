import type { RunMode } from "./models";
export interface ManagedTask {
  id: string;
  kind: string;
  sourceId: string;
  projectId: string;
  sessionId: string | null;
  runId: string | null;
  status: string;
  mode?: RunMode | null;
  startedAt?: string | null;
  updatedAt?: string | null;
  waitingReason?: string | null;
  errorSummary?: string | null;
  progress?: { completed: number; total: number } | null;
  results: { kind: string; id: string }[];
  goalId?: string | null;
  dependencyIds: string[];
  schedulerId?: string | null;
  parentId?: string | null;
  childIds: string[];
  checkpointTaskId?: string | null;
  cancelSupported: boolean;
  suspendSupported?: boolean;
  resumeSupported: boolean;
  inputSupported: boolean;
  revision: number;
}
export interface TaskPage {
  items: ManagedTask[];
  nextCursor: string | null;
}
export type TaskAction = "cancel" | "suspend" | "resume" | "input";
