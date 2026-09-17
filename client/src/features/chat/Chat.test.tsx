import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { Chat } from "./Chat";
import type { Conversation, Run } from "../../entities/models";
afterEach(cleanup);

it("interleaves text and event snapshots in native output order", () => {
  const tool = { id: "c", name: "searchFiles", status: "RUNNING", summary: "" };
  const live: Run = {
    ...run,
    assistantText: "before after",
    timeline: [
      { kind: "text", id: "1", messageId: "m", text: "before" },
      { kind: "tool", id: "2", tool },
      { kind: "text", id: "3", messageId: "m", text: " after" },
      {
        kind: "tool",
        id: "4",
        tool: { ...tool, status: "COMPLETED", durationMs: 18 },
      },
    ],
  };
  const { container } = render(
    <Chat
      conversation={conversation}
      projectName="rei"
      runs={[live]}
      pending={false}
      error={null}
      onSend={vi.fn()}
      onStop={vi.fn()}
      onContinue={vi.fn()}
      onRefresh={vi.fn()}
      onSubscribe={vi.fn()}
    />,
  );
  const rows = Array.from(container.querySelectorAll(".run-timeline > *"));
  expect(rows.map((row) => row.textContent)).toEqual([
    "before",
    expect.stringContaining("RUNNING"),
    " after",
    expect.stringContaining("COMPLETED"),
  ]);
  expect(screen.queryByText("before after")).toBeNull();
  expect(container.querySelector(".live-activity")).toBeNull();
});
const conversation: Conversation = {
  localId: "c",
  serverProfileId: "s",
  projectId: "p",
  sessionId: "session",
  title: "Design review",
  createdAt: 0,
  lastAccessedAt: 0,
};
const run: Run = {
  serverId: "s",
  runId: "r",
  conversationId: "c",
  projectId: "p",
  sessionId: "session",
  turnId: "t",
  prompt: "hello",
  assistantText: "回答",
  status: "RUNNING",
  streamState: "RECONNECTING",
  lastSequence: "2",
  incomplete: true,
  error: null,
  failure: null,
  tools: [
    { id: "tool", name: "readFile", status: "COMPLETED", summary: "a.rs" },
  ],
  activities: [],
  messages: [],
  workingSet: [],
  revision: 1,
};
it("shows a locked project, partial history, reconnect and an explicit run stop", () => {
  const stop = vi.fn();
  render(
    <Chat
      conversation={conversation}
      projectName="rei"
      runs={[run]}
      pending={false}
      error={null}
      onSend={vi.fn()}
      onStop={stop}
      onContinue={vi.fn()}
      onRefresh={vi.fn()}
      onSubscribe={vi.fn()}
    />,
  );
  expect(screen.getByText("rei 🔒")).toBeTruthy();
  expect(screen.getByText(/イベント履歴の一部/)).toBeTruthy();
  expect(screen.getByText("RECONNECTING")).toBeTruthy();
  expect(screen.getByText("readFile")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Stop" }));
  expect(stop).toHaveBeenCalledWith(run);
});
it("requires an explicit action to continue an expired session", () => {
  const next = vi.fn();
  render(
    <Chat
      conversation={conversation}
      projectName="rei"
      runs={[]}
      pending={false}
      error="SessionNotFound"
      onSend={vi.fn()}
      onStop={vi.fn()}
      onContinue={next}
      onRefresh={vi.fn()}
      onSubscribe={vi.fn()}
    />,
  );
  expect(next).not.toHaveBeenCalled();
  fireEvent.click(
    screen.getByRole("button", { name: "新しい会話として続ける" }),
  );
  expect(next).toHaveBeenCalledOnce();
});

it("renders live projection activity, collapses it, and updates tool lifecycle and streamed answer", () => {
  const props = {
    conversation,
    projectName: "rei",
    pending: false,
    error: null,
    onSend: vi.fn(),
    onStop: vi.fn(),
    onContinue: vi.fn(),
    onRefresh: vi.fn(),
    onSubscribe: vi.fn(),
  };
  const activity = {
    id: "llm:q",
    category: "LLM",
    label: "LLM request",
    status: "RUNNING",
    summary: "chat",
    startedAt: null,
    completedAt: null,
    durationMs: null,
    firstTokenMs: 70,
    error: null,
    metrics: [],
  };
  const live = {
    ...run,
    assistantText: "hel",
    tools: [{ id: "tool", name: "readFile", status: "RUNNING", summary: "" }],
    activities: [
      activity,
      {
        ...activity,
        id: "progress:1",
        category: "Progress",
        label: "No progress",
        firstTokenMs: null,
        metrics: [
          { label: "No progress", value: 1 },
          { label: "Threshold", value: 4 },
        ],
      },
    ],
  };
  const { rerender } = render(<Chat {...props} runs={[live]} />);
  const summary = screen.getByText("Activity · 3");
  const details = summary.closest("details")!;
  expect(details.open).toBe(false);
  fireEvent.click(summary);
  expect(details.open).toBe(true);
  expect(screen.getByText("First token: 70 ms")).toBeTruthy();
  expect(screen.getByText("No progress: 1")).toBeTruthy();
  expect(screen.getByText("Threshold: 4")).toBeTruthy();
  expect(screen.getByText("hel")).toBeTruthy();
  const finished = {
    ...live,
    status: "COMPLETED" as const,
    assistantText: "hello world",
    activities: [{ ...activity, status: "COMPLETED", durationMs: 210 }],
    tools: [{ ...live.tools[0], status: "COMPLETED", durationMs: 18 }],
  };
  rerender(<Chat {...props} runs={[finished]} />);
  expect(screen.getByText("18 ms")).toBeTruthy();
  expect(screen.getByText("210 ms")).toBeTruthy();
  expect(screen.getByText("hello world")).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Stop" })).toBeNull();
  rerender(
    <Chat
      {...props}
      runs={[
        {
          ...finished,
          tools: [
            {
              ...finished.tools[0],
              status: "FAILED",
              error: { type: "operation_failed", message: "Operation failed." },
            },
          ],
        },
      ]}
    />,
  );
  expect(screen.getByText("Operation failed.")).toBeTruthy();
  fireEvent.click(screen.getByText("Activity · 2"));
  expect(details.open).toBe(false);
});

it("historical turns render only user and assistant without activity", () => {
  render(
    <Chat
      conversation={conversation}
      projectName="rei"
      runs={[]}
      pending={false}
      error={null}
      onSend={vi.fn()}
      onStop={vi.fn()}
      onContinue={vi.fn()}
      onRefresh={vi.fn()}
      onSubscribe={vi.fn()}
      history={{
        items: [
          {
            turnId: "old",
            runId: "old",
            userMessage: "Past question",
            assistantMessage: "Past answer",
            createdAt: "2026-09-17",
          },
        ],
        status: "LOADED",
        loadingInitial: false,
        loadingMore: false,
        refreshing: false,
        stale: false,
        error: null,
        nextCursor: null,
      }}
    />,
  );
  expect(screen.getByText("Past question")).toBeTruthy();
  expect(screen.getByText("Past answer")).toBeTruthy();
  expect(screen.queryByText(/Activity/)).toBeNull();
});

it("shows the stored final answer when a replay gap left only events", () => {
  render(
    <Chat
      conversation={conversation}
      projectName="rei"
      pending={false}
      error={null}
      onSend={vi.fn()}
      onStop={vi.fn()}
      onContinue={vi.fn()}
      onRefresh={vi.fn()}
      onSubscribe={vi.fn()}
      runs={[
        {
          ...run,
          status: "COMPLETED",
          assistantText: "",
          incomplete: true,
          timeline: [{ kind: "tool", id: "1", tool: run.tools[0] }],
        },
      ]}
      history={{
        items: [
          {
            turnId: "t",
            runId: "r",
            userMessage: "hello",
            assistantMessage: "Recovered final answer",
            createdAt: "2026-09-17",
          },
        ],
        status: "LOADED",
        loadingInitial: false,
        loadingMore: false,
        refreshing: false,
        stale: false,
        error: null,
        nextCursor: null,
      }}
    />,
  );
  expect(screen.getByText("Recovered final answer")).toBeTruthy();
});
