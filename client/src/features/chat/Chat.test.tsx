import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { Chat } from "./Chat";
import type { Conversation, Run } from "../../entities/models";
afterEach(cleanup);
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
