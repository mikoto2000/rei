import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { SessionList } from "./SessionList";
import type { HistoryState } from "./pagination";
import type { SessionSummary } from "../../entities/models";
afterEach(() => {
  cleanup();
  localStorage.clear();
});
it("searches loaded titles and lets users pin, archive, and restore a conversation", () => {
  const state: HistoryState<SessionSummary> = {
    status: "LOADED",
    items: [
      {
        sessionId: "a",
        projectId: "p",
        title: "設計相談",
        createdAt: "2026-11-24",
        updatedAt: "2026-11-24",
      },
      {
        sessionId: "b",
        projectId: "p",
        title: "不具合調査",
        createdAt: "2026-11-24",
        updatedAt: "2026-11-24",
      },
    ],
    nextCursor: null,
    loadingInitial: false,
    loadingMore: false,
    refreshing: false,
    error: null,
    stale: false,
  };
  render(
    <SessionList
      state={state}
      projects={[]}
      projectId={null}
      onProject={vi.fn()}
      onMore={vi.fn()}
      onRefresh={vi.fn()}
      onRetry={vi.fn()}
      onOpen={vi.fn()}
      onNew={vi.fn()}
      enabled
    />,
  );
  fireEvent.change(screen.getByLabelText("会話を検索"), {
    target: { value: "設計" },
  });
  expect(screen.queryByText("不具合調査")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "ピン留め" }));
  expect(screen.getByRole("button", { name: "ピン留めを解除" })).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "アーカイブ" }));
  expect(screen.queryByText("設計相談")).toBeNull();
  fireEvent.click(screen.getByLabelText("アーカイブした会話を表示"));
  fireEvent.click(screen.getByRole("button", { name: "アーカイブから戻す" }));
  expect(screen.queryByText("設計相談")).toBeNull();
  fireEvent.click(screen.getByLabelText("アーカイブした会話を表示"));
  expect(screen.getByText("設計相談")).toBeTruthy();
});

it("keeps conversation organization isolated by server", () => {
  localStorage.setItem(
    "rei:history:first",
    JSON.stringify({ a: { archived: true } }),
  );
  const state: HistoryState<SessionSummary> = {
    status: "LOADED",
    items: [
      {
        sessionId: "a",
        projectId: "p",
        title: "別サーバーの会話",
        createdAt: "2026-11-24",
        updatedAt: "2026-11-24",
      },
    ],
    nextCursor: null,
    loadingInitial: false,
    loadingMore: false,
    refreshing: false,
    error: null,
    stale: false,
  };
  render(
    <SessionList
      serverId="second"
      state={state}
      projects={[]}
      projectId={null}
      onProject={vi.fn()}
      onMore={vi.fn()}
      onRefresh={vi.fn()}
      onRetry={vi.fn()}
      onOpen={vi.fn()}
      onNew={vi.fn()}
      enabled
    />,
  );
  expect(screen.getByText("別サーバーの会話")).toBeTruthy();
});
