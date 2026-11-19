import { renderHook, cleanup } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { HistoryPager } from "./pagination";
import { useTerminalHistoryRefresh } from "./useHistory";
import type {
  Conversation,
  Run,
  SessionSummary,
  ConversationTurn,
} from "../../entities/models";
afterEach(cleanup);
it.each(["WAITING", "WAITING_APPROVAL"] as const)(
  "refreshes only after a %s run reaches a terminal state",
  (waiting) => {
    const list = new HistoryPager<
      SessionSummary,
      { serverId: string; projectId: string | null }
    >(vi.fn(), (r) => r.sessionId);
    const turns = new HistoryPager<
      ConversationTurn,
      { serverId: string; sessionId: string }
    >(vi.fn(), (r) => r.runId);
    const ls = vi.spyOn(list, "refresh");
    const ts = vi.spyOn(turns, "refresh");
    const reload = vi.fn().mockResolvedValue(undefined);
    const conversation = {
      serverProfileId: "s",
      sessionId: "session",
    } as Conversation;
    const run = {
      serverId: "s",
      sessionId: "session",
      runId: "r",
      status: "RUNNING",
    } as Run;
    const { rerender } = renderHook(
      ({ current }) =>
        useTerminalHistoryRefresh(
          [current],
          "s",
          conversation,
          list,
          turns,
          reload,
        ),
      { initialProps: { current: run } },
    );
    rerender({ current: { ...run, status: waiting } });
    expect(ls).not.toHaveBeenCalled();
    expect(ts).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
    rerender({ current: { ...run, status: "RUNNING" } });
    rerender({ current: { ...run, status: "COMPLETED" } });
    rerender({ current: { ...run, status: "COMPLETED", revision: 4 } });
    expect(ls).toHaveBeenCalledTimes(1);
    expect(ts).toHaveBeenCalledTimes(1);
    expect(reload).toHaveBeenCalledTimes(1);
  },
);
it("refreshes metadata and history once at terminal, never for message deltas", () => {
  const list = new HistoryPager<
    SessionSummary,
    { serverId: string; projectId: string | null }
  >(vi.fn(), (r) => r.sessionId);
  const turns = new HistoryPager<
    ConversationTurn,
    { serverId: string; sessionId: string }
  >(vi.fn(), (r) => r.runId);
  const ls = vi.spyOn(list, "refresh"),
    ts = vi.spyOn(turns, "refresh"),
    reload = vi.fn().mockResolvedValue(undefined);
  const c = { serverProfileId: "s", sessionId: "session" } as Conversation;
  const r = {
    serverId: "s",
    sessionId: "session",
    runId: "r",
    status: "RUNNING",
  } as Run;
  const { rerender } = renderHook(
    ({ run }) => useTerminalHistoryRefresh([run], "s", c, list, turns, reload),
    { initialProps: { run: r } },
  );
  rerender({ run: { ...r, assistantText: "delta" } });
  expect(ls).not.toHaveBeenCalled();
  rerender({ run: { ...r, status: "COMPLETED" } });
  rerender({ run: { ...r, status: "COMPLETED", revision: 4 } });
  expect(ls).toHaveBeenCalledTimes(1);
  expect(ts).toHaveBeenCalledTimes(1);
  expect(reload).toHaveBeenCalledTimes(1);
});
