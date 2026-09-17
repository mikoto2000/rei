import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
  within,
} from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { App } from "./App";
import type { Command } from "./tauri/commands";
import type {
  Snapshot,
  SessionSummary,
  Conversation,
  Run,
} from "./entities/models";
afterEach(cleanup);
const summary: SessionSummary = {
  sessionId: "remote",
  projectId: "p",
  title: "別端末の会話",
  createdAt: "2026-09-16T08:00:00Z",
  updatedAt: "2026-09-16T10:00:00Z",
};
const conversation: Conversation = {
  localId: "c",
  serverProfileId: "s",
  projectId: "p",
  sessionId: "remote",
  title: summary.title,
  createdAt: 0,
  lastAccessedAt: 0,
};
function fixture(overrides: Record<string, unknown> = {}) {
  const data: Snapshot = {
    servers: [
      {
        id: "s",
        name: "Home",
        baseUrl: "https://rei.example",
        hasCredential: true,
      },
    ],
    selectedServer: "s",
    conversations: [],
    runs: [],
    unlocked: true,
    notifications: false,
  };
  const call = vi.fn(
    async (name: string, args: Record<string, unknown> | undefined) => {
      if (name in overrides) {
        const value = overrides[name];
        if (typeof value === "function") return value(args);
        return value;
      }
      if (name === "app_snapshot")
        return {
          ...data,
          conversations: [...data.conversations],
          runs: [...data.runs],
        };
      if (name === "projects_list")
        return [
          { id: "p", name: "rei", path: "/rei" },
          { id: "q", name: "other", path: "/other" },
        ];
      if (name === "session_list")
        return args?.cursor
          ? {
              items: [{ ...summary, sessionId: "second", title: "次の会話" }],
              nextCursor: null,
            }
          : { items: [summary], nextCursor: "opaque" };
      if (name === "session_open") {
        data.conversations = [conversation];
        return conversation;
      }
      if (name === "session_turns")
        return args?.cursor
          ? {
              items: [
                {
                  turnId: "two",
                  runId: "two",
                  userMessage: "次の質問",
                  assistantMessage: "次の回答",
                  createdAt: summary.updatedAt,
                },
              ],
              nextCursor: null,
            }
          : {
              items: [
                {
                  turnId: "one",
                  runId: "one",
                  userMessage: "過去の質問",
                  assistantMessage: "過去の回答",
                  createdAt: summary.createdAt,
                },
              ],
              nextCursor: "turn-cursor",
            };
      if (name === "chat_submit") {
        const run: Run = {
          serverId: "s",
          conversationId: "c",
          projectId: "p",
          sessionId: "remote",
          runId: "new",
          turnId: "new",
          prompt: String(args?.message),
          status: "COMPLETED",
          streamState: "CLOSED",
          assistantText: "新しい回答",
          lastSequence: "2",
          incomplete: false,
          error: null,
          failure: null,
          tools: [],
          activities: [],
          messages: [],
          workingSet: [],
          revision: 2,
        };
        data.runs = [run];
        return run;
      }
    },
  );
  render(
    <App
      call={call as Command}
      subscriptions={{
        runs: async () => () => {},
        connection: async () => () => {},
      }}
    />,
  );
  return { call, data };
}
const main = () => within(screen.getByRole("main"));
it("lists remote sessions, loads more, filters by project ID and refreshes from first page", async () => {
  const { call } = fixture();
  await main().findByRole("button", { name: /別端末の会話/ });
  fireEvent.click(main().getByRole("button", { name: "会話をもっと読み込む" }));
  await main().findByText("次の会話");
  fireEvent.change(main().getByLabelText("会話のプロジェクト"), {
    target: { value: "q" },
  });
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("session_list", {
      serverId: "s",
      projectId: "q",
      limit: 50,
      cursor: null,
    }),
  );
  fireEvent.click(main().getByRole("button", { name: "会話一覧を更新" }));
  await waitFor(() =>
    expect(call.mock.calls.filter((c) => c[0] === "session_list")).toHaveLength(
      4,
    ),
  );
});
it("opens server turns, appends next chronological page and resumes with locked project", async () => {
  const { call } = fixture();
  fireEvent.click(await main().findByRole("button", { name: /別端末の会話/ }));
  await screen.findByText("過去の回答");
  expect(screen.queryByRole("radio")).toBeNull();
  expect(screen.getByText("rei 🔒")).toBeTruthy();
  fireEvent.click(
    screen.getByRole("button", { name: "次のメッセージを読み込む" }),
  );
  await screen.findByText("次の回答");
  expect(call).toHaveBeenCalledWith("session_turns", {
    serverId: "s",
    sessionId: "remote",
    limit: 50,
    cursor: "turn-cursor",
  });
  fireEvent.change(screen.getByLabelText("メッセージ"), {
    target: { value: "続けて" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Send ↗" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("chat_submit", {
      conversationId: "c",
      message: "続けて",
    }),
  );
  await screen.findByText("新しい回答");
  fireEvent.click(screen.getByRole("button", { name: "会話一覧へ戻る" }));
  await main().findByRole("button", { name: /別端末の会話/ });
});
it("shows empty, initial loading, errors and explicit retry", async () => {
  let resolve!: (value: unknown) => void;
  const pending = new Promise((r) => {
    resolve = r;
  });
  fixture({ session_list: () => pending });
  await screen.findByText("会話を読み込んでいます…");
  resolve({ items: [], nextCursor: null });
  await screen.findByText("まだ会話がありません");
});
it("open 404 offers recovery without creating or submitting a session", async () => {
  const { call } = fixture({
    session_open: () => Promise.reject("SessionNotFound"),
  });
  fireEvent.click(await main().findByRole("button", { name: /別端末の会話/ }));
  await screen.findByText(/サーバー上でこの会話セッションが存在しません/);
  expect(
    call.mock.calls.some(
      (c) => c[0] === "chat_submit" || c[0] === "conversation_create",
    ),
  ).toBe(false);
  expect(screen.getByRole("button", { name: "会話一覧へ戻る" })).toBeTruthy();
});
it("submit 404 preserves the draft and never forks automatically", async () => {
  const { call } = fixture({
    chat_submit: () => Promise.reject("SessionNotFound"),
  });
  fireEvent.click(await main().findByRole("button", { name: /別端末の会話/ }));
  await screen.findByText("過去の回答");
  fireEvent.change(screen.getByLabelText("メッセージ"), {
    target: { value: "消さない" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Send ↗" }));
  await screen.findByText(/サーバー上でこの会話セッションが存在しません/);
  expect(screen.getByLabelText("メッセージ")).toHaveProperty(
    "value",
    "消さない",
  );
  expect(call.mock.calls.filter((c) => c[0] === "chat_submit")).toHaveLength(1);
});
