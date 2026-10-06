export interface CoachingSettings {
  enabled: boolean;
  categories: string[];
  targetShare: number;
  minimumObservedMinutes: number;
  minimumCoverage: number;
  maximumUnknownShare: number;
  cooldownDays: number;
}
export type WorkspaceOperation =
  | { operation: "activityCoachingSettings" }
  | {
      operation: "activityCoachingConfigure";
      expectedRevision: number;
      settings: CoachingSettings;
    }
  | {
      operation: "activityCoachingEnabled";
      expectedRevision: number;
      enabled: boolean;
    }
  | {
      operation: "activityAnalysis";
      period: "WEEK" | "MONTH";
      date: string | null;
    }
  | { operation: "goals"; projectId: string }
  | {
      operation:
        "goal" | "goalHistory" | "goalVerify" | "goalRun" | "goalCancel";
      projectId: string;
      id: string;
    }
  | {
      operation: "goalReconcile";
      projectId: string;
      id: string;
      expectedRunId: string;
      acknowledgeUncertainSideEffects: boolean;
    }
  | { operation: "schedules"; projectId: string }
  | {
      operation:
        "schedule" | "scheduleHistory" | "scheduleActivate" | "scheduleCancel";
      projectId: string;
      id: string;
    }
  | {
      operation: "scheduleReconcile";
      projectId: string;
      id: string;
      expectedRunId: string;
      acknowledgeUncertainSideEffects: boolean;
    }
  | { operation: "dependencies"; projectId: string }
  | {
      operation: "dependencyAnswer";
      projectId: string;
      id: string;
      expectedVersion: number;
      answer: string;
    }
  | { operation: "checkpoints"; projectId: string }
  | {
      operation: "checkpoint" | "checkpointInspect" | "checkpointAbandon";
      projectId: string;
      taskId: string;
    }
  | { operation: "attention" | "approvals"; projectId: string }
  | { operation: "attentionAck"; projectId: string; id: string }
  | {
      operation: "approvalDecision";
      projectId: string;
      id: string;
      approved: boolean;
    }
  | { operation: "workContext" | "workContextHistory"; projectId: string }
  | { operation: "workContextUpdate"; sessionId: string }
  | {
      operation:
        | "feeds"
        | "skills"
        | "profile"
        | "briefing"
        | "reminders"
        | "memories"
        | "reloadSkills";
    }
  | {
      operation: "feed" | "reminder" | "deleteFeed" | "deleteReminder";
      id: number;
    }
  | { operation: "skill"; name: string }
  | { operation: "memory" | "deleteMemory"; id: string }
  | {
      operation: "search";
      query: string;
      vectorTopK: number;
      webTopK: number;
      threshold: number;
    }
  | { operation: "createFeed"; url: string; displayName: string | null }
  | {
      operation: "updateFeed";
      id: number;
      displayName: string | null;
      enabled: boolean | null;
    }
  | {
      operation: "createReminder";
      message: string;
      at: string | null;
      target: string | null;
      minutesBefore: number | null;
    }
  | { operation: "interests"; hours: number }
  | {
      operation: "createInterest";
      topic: string;
      reason: string;
      searchQuery: string;
      summary: string;
      sourceUrls: string[];
    }
  | {
      operation: "createMemory";
      content: string;
      memoryType: string;
      scope: string;
      confidence: number;
    };
export type BackgroundOperation =
  | { operation: "summary"; url: string }
  | { operation: "image"; prompt: string; size: string | null };
export interface WorkspaceResult {
  title: string;
  items: { id: string | null; title: string; fields: [string, string][] }[];
}
