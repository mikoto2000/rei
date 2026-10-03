export type WorkspaceOperation =
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
