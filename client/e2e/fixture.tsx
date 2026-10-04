import ReactDOM from "react-dom/client";
import { App } from "../src/App";
import type { Command } from "../src/tauri/commands";
import type { Snapshot, Run } from "../src/entities/models";
import "../src/styles.css";
const server = {
  id: "s",
  name: "Home Rei",
  baseUrl: "https://rei.example",
  hasCredential: true,
};
const conversation = {
  localId: "c",
  serverProfileId: "s",
  projectId: "p",
  sessionId: "session",
  title: "Web API の設計について",
  createdAt: 1,
  lastAccessedAt: Date.now(),
};
const run: Run = {
  serverId: "s",
  conversationId: "c",
  projectId: "p",
  runId: "r",
  sessionId: "session",
  turnId: "t",
  prompt: "SSE の再接続と認証まわりを確認して",
  status: "RUNNING",
  streamState: "CONNECTED",
  assistantText:
    "API の構造を確認しています。\n\nRun ごとのイベントを読み取り、再接続時の sequence と重複排除を調べます。",
  lastSequence: "102",
  incomplete: false,
  error: null,
  failure: null,
  tools: [
    {
      id: "t",
      name: "readFile",
      status: "RUNNING",
      summary: "src/main/java/rei/web/SseController.java",
    },
  ],
  activities: [
    {
      id: "llm:q",
      category: "LLM",
      label: "LLM request",
      status: "RUNNING",
      summary: "chat",
      startedAt: "2026-09-17T01:00:00Z",
      completedAt: null,
      durationMs: null,
      firstTokenMs: 70,
      error: null,
      metrics: [],
    },
    {
      id: "progress:1",
      category: "Progress",
      label: "No progress",
      status: "COMPLETED",
      summary: "",
      startedAt: null,
      completedAt: null,
      durationMs: null,
      firstTokenMs: null,
      error: null,
      metrics: [
        { label: "No progress", value: 1 },
        { label: "Threshold", value: 4 },
      ],
    },
    {
      id: "skill:selection",
      category: "Skill",
      label: "Selection",
      status: "COMPLETED",
      summary: "coding",
      startedAt: null,
      completedAt: null,
      durationMs: 18,
      firstTokenMs: null,
      error: null,
      metrics: [],
    },
    {
      id: "ws:removed",
      category: "Working Set",
      label: "Removed",
      status: "COMPLETED",
      summary: "Old.java",
      startedAt: null,
      completedAt: null,
      durationMs: null,
      firstTokenMs: null,
      error: null,
      metrics: [],
    },
  ],
  messages: [],
  workingSet: [
    {
      id: "w",
      path: "src/main/java/rei/web/SseController.java",
      kind: "file",
      identifier: "SseController",
    },
  ],
  revision: 1,
};
const data: Snapshot = {
  servers: [server],
  selectedServer: "s",
  conversations: [conversation],
  runs: [run],
  unlocked: true,
  notifications: false,
};
if (new URLSearchParams(location.search).has("timeline")) {
  run.timeline = [
    { kind: "text", id: "1", messageId: "m", text: "ファイルを調べます。" },
    { kind: "tool", id: "2", tool: { ...run.tools[0], status: "RUNNING" } },
    { kind: "activity", id: "3", activity: run.activities[0] },
    { kind: "text", id: "4", messageId: "m", text: "構造が分かりました。" },
    {
      kind: "tool",
      id: "5",
      tool: { ...run.tools[0], status: "COMPLETED", durationMs: 18 },
    },
  ];
}
let onRun: (run: Run) => void = () => {};
let checkpointResumed = false;
let checkpointAbandoned = false;
let humanAnswer: string | null = null;
let goalStatus =
  new URLSearchParams(location.search).get("goal") === "uncertain"
    ? "RUNNING"
    : "READY";
let goalCurrentRun = goalStatus === "RUNNING" ? "old-goal-run" : "";
let acknowledged = false;
let decided = false;
const call = (async (
  name: string,
  args: Record<string, unknown> | undefined,
) => {
  if (name === "app_snapshot") return structuredClone(data);
  if (name === "goal_track") {
    if (
      args?.serverId !== "s" ||
      args?.projectId !== "p" ||
      args?.goalId !== "g"
    )
      throw "InvalidInput";
    if (goalCurrentRun !== "goal-run") throw "RunNotFound";
    const accepted: Run = {
      ...run,
      conversationId: "",
      runId: "goal-run",
      sessionId: "session",
      turnId: null,
      prompt: "Goal g",
      status: "QUEUED",
      revision: 1,
      assistantText: "",
      tools: [],
      activities: [],
      timeline: [],
      messages: [],
      workingSet: [],
    };
    onRun(accepted);
    return accepted;
  }
  if (name === "checkpoint_resume" || name === "checkpoint_track") {
    if (
      args?.serverId !== "s" ||
      args?.projectId !== "p" ||
      args?.taskId !== "checkpoint-task"
    )
      throw "InvalidInput";
    if (name === "checkpoint_resume") checkpointResumed = true;
    if (!checkpointResumed) throw "RunNotFound";
    const accepted: Run = {
      ...run,
      conversationId: "",
      runId: "checkpoint-run",
      sessionId: "session",
      turnId: null,
      prompt: "Checkpoint checkpoint-task",
      status: "QUEUED",
      revision: 1,
      assistantText: "",
      tools: [],
      activities: [],
      timeline: [],
      messages: [],
      workingSet: [],
    };
    onRun(accepted);
    return accepted;
  }
  if (name === "workspace_execute") {
    const op = args?.operation as {
      operation: string;
      url?: string;
      displayName?: string;
      projectId?: string;
      taskId?: string;
      id?: string;
      approved?: boolean;
      expectedVersion?: number;
      answer?: string;
      expectedRunId?: string;
      acknowledgeUncertainSideEffects?: boolean;
    };
    if (
      [
        "goals",
        "goal",
        "goalHistory",
        "goalVerify",
        "goalRun",
        "goalCancel",
        "goalReconcile",
      ].includes(op.operation)
    ) {
      if (op.projectId !== "p" || (op.operation !== "goals" && op.id !== "g"))
        throw "ProjectNotFound";
      if (op.operation === "goalRun") {
        goalStatus = "RUNNING";
        goalCurrentRun = "goal-run";
      }
      if (op.operation === "goalCancel") goalStatus = "CANCELLED";
      if (op.operation === "goalVerify") goalStatus = "COMPLETED";
      if (op.operation === "goalReconcile") {
        if (
          op.expectedRunId !== goalCurrentRun ||
          !op.acknowledgeUncertainSideEffects
        )
          throw "ResourceConflict";
        goalStatus = "PAUSED";
      }
      const fields = [
        ["Project", "p"],
        ["Session", "session"],
        ["Status", goalStatus],
        ["Run", goalCurrentRun],
        ["Runs", "1 / 3"],
        ["LLM calls", "2 / 20"],
        ["Criteria", "out.txt must match saved SHA-256"],
        ["Reason", goalStatus === "PAUSED" ? "uncertain_run_reconciled" : ""],
      ];
      if (op.operation === "goalHistory")
        fields.push(
          ["History", "saved_goal_history"],
          ["Attempts", "saved_attempt_history"],
        );
      if (op.operation === "goalVerify")
        fields.push(
          ["Satisfied", "true"],
          ["Verification", "file_digest_verified"],
        );
      return {
        title: "Goal",
        items: [{ id: "g", title: "Fixture Goal outcome", fields }],
      };
    }
    if (
      op.operation === "dependencies" ||
      op.operation === "dependencyAnswer"
    ) {
      if (op.projectId !== "p") throw "ProjectNotFound";
      if (op.operation === "dependencyAnswer") {
        if (
          op.id !== "dep" ||
          op.expectedVersion !== (humanAnswer === null ? 3 : 4) ||
          !op.answer
        )
          throw "ResourceConflict";
        humanAnswer = op.answer;
      }
      return {
        title: "Dependencies",
        items: [
          {
            id: "dep",
            title: "Fixture question: choose A or B",
            fields: [
              ["Project", "p"],
              ["Session", "session"],
              ["Kind", "USER_ANSWER"],
              ["State", "WAITING"],
              ["Version", humanAnswer === null ? "3" : "4"],
              ["Answer", humanAnswer ?? ""],
              ["Deadline", "2026-10-05T00:00:00Z"],
              ["Reason", "user_answer_waiting"],
              ["Prerequisites", ""],
            ],
          },
        ],
      };
    }
    if (
      ["attention", "approvals", "attentionAck", "approvalDecision"].includes(
        op.operation,
      )
    ) {
      if (op.projectId !== "p") throw "ProjectNotFound";
      if (op.operation === "attentionAck") {
        if (op.id !== "notice") throw "InvalidInput";
        acknowledged = true;
        return { title: "ack", items: [] };
      }
      if (op.operation === "approvalDecision") {
        if (op.id !== "request" || typeof op.approved !== "boolean")
          throw "InvalidInput";
        decided = true;
        return { title: "decided", items: [] };
      }
      return {
        title: "Inbox",
        items:
          op.operation === "attention"
            ? acknowledged
              ? []
              : [
                  {
                    id: "notice",
                    title: "RUN_FAILED",
                    fields: [
                      ["Project", "p"],
                      ["Session", "session"],
                      ["Status", "OPEN"],
                      ["Message", "Fixture failure requires review"],
                    ],
                  },
                ]
            : decided
              ? []
              : [
                  {
                    id: "request",
                    title: "writeFile",
                    fields: [
                      ["Project", "p"],
                      ["Session", "session"],
                      ["Run", "r"],
                      ["Status", "PENDING"],
                      ["Arguments", "Fixture redacted arguments"],
                      ["Expires", "2026-10-04T12:00:00Z"],
                    ],
                  },
                ],
      };
    }
    if (
      [
        "checkpoints",
        "checkpoint",
        "checkpointInspect",
        "checkpointAbandon",
      ].includes(op.operation)
    ) {
      if (op.projectId !== "p") throw "ProjectNotFound";
      if (op.operation === "checkpointAbandon") checkpointAbandoned = true;
      const state = {
        id: "checkpoint-task",
        title: "Fixture checkpoint outcome",
        fields: [
          ["Project", "p"],
          ["Session", "session"],
          ["Run", checkpointResumed ? "checkpoint-run" : "old"],
          ["Revision", "3"],
          ["Status", checkpointAbandoned ? "ABANDONED" : "INTERRUPTED"],
        ],
      };
      return {
        title: "Checkpoint",
        items:
          op.operation === "checkpointInspect"
            ? [
                {
                  id: "checkpoint-task",
                  title: "Reconciliation",
                  fields: [
                    ["Project", "p"],
                    ["Decision", "CONFIRMATION_REQUIRED"],
                    ["Changed", "Fixture file changed"],
                    [
                      "Unknown operations",
                      "writeFile result requires confirmation",
                    ],
                    ["Next", "Verify before repeating effects"],
                  ],
                },
              ]
            : [state],
      };
    }
    if (op.operation !== "feeds" && op.operation !== "createFeed")
      throw "InvalidInput";
    return {
      title: "Feed",
      items: [
        {
          id: "1",
          title: op.operation === "feeds" ? "Fixture feed" : op.displayName,
          fields: [
            ["URL", op.url ?? "https://example.com/rss"],
            ["Enabled", "true"],
          ],
        },
      ],
    };
  }
  if (name === "background_submit") {
    const op = args?.operation as { operation: string; url: string };
    if (args?.projectId !== "p" || op.operation !== "summary")
      throw "InvalidInput";
    const background: Run = {
      ...run,
      runId: "background",
      conversationId: "",
      sessionId: null,
      turnId: null,
      prompt: `Summary: ${op.url}`,
      status: "COMPLETED",
      streamState: "CLOSED",
      assistantText: "Fixture summary result",
      timeline: [],
      activities: [],
      tools: [],
    };
    data.runs.push(background);
    return structuredClone(background);
  }
  if (name === "session_list")
    return {
      items: [
        {
          sessionId: "session",
          projectId: "p",
          title: conversation.title,
          createdAt: "2026-09-16T00:00:00Z",
          updatedAt: "2026-09-16T01:00:00Z",
        },
      ],
      nextCursor: null,
    };
  if (name === "session_open") return conversation;
  if (name === "session_turns")
    return {
      items: [
        {
          turnId: args?.cursor ? "older2" : "older1",
          runId: args?.cursor ? "older2" : "older1",
          userMessage: args?.cursor ? "次の保存済み質問" : "保存済みの質問",
          assistantMessage: "保存済みの回答",
          createdAt: "2026-09-16T00:00:00Z",
        },
      ],
      nextCursor: args?.cursor ? null : "opaque-next",
    };
  if (name === "projects_list")
    return [{ id: "p", name: "rei", path: "F:\\project\\rei" }];
  if (name === "run_cancel") {
    if (args?.runId !== "r") throw "RunNotFound";
    data.runs[0] = {
      ...run,
      status: "CANCELLED",
      streamState: "CLOSED",
      revision: 2,
    };
    onRun(data.runs[0]);
  }
  if (name === "run_get") {
    const failed = data.runs[0].status === "COMPLETED";
    data.runs[0] = {
      ...data.runs[0],
      revision: data.runs[0].revision + 1,
      status: failed ? "FAILED" : "COMPLETED",
      streamState: "CLOSED",
      assistantText: "確認が完了しました。",
      tools: [
        {
          ...run.tools[0],
          status: failed ? "FAILED" : "COMPLETED",
          durationMs: 18,
          error: failed
            ? { type: "operation_failed", message: "Operation failed." }
            : null,
        },
      ],
      activities: run.activities.map((a) =>
        a.category === "LLM"
          ? { ...a, status: "COMPLETED", durationMs: 210 }
          : a,
      ),
    };
    onRun(data.runs[0]);
    return data.runs[0];
  }
  if (name === "conversation_create") {
    const c = {
      ...conversation,
      localId: "new",
      sessionId: null,
      title: "新しい会話",
    };
    data.conversations.push(c);
    return c;
  }
  if (name === "server_test")
    return {
      serverId: "s",
      state: "CONNECTED",
      reachable: true,
      authenticated: true,
      error: null,
    };
}) as Command;
ReactDOM.createRoot(document.getElementById("root")!).render(
  <App
    call={call}
    subscriptions={{
      runs: async (cb) => {
        onRun = cb;
        return () => {};
      },
      connection: async () => () => {},
    }}
  />,
);
