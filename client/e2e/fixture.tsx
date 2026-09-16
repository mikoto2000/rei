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
      status: "COMPLETED",
      summary: "src/main/java/rei/web/SseController.java",
    },
  ],
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
let onRun: (run: Run) => void = () => {};
const call = (async (
  name: string,
  args: Record<string, unknown> | undefined,
) => {
  if (name === "app_snapshot") return structuredClone(data);
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
