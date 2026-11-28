import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import type {
  WorkspaceOperation,
  WorkspaceResult,
} from "../../entities/workspace";
import { AutonomousHistory } from "./AutonomousHistory";

afterEach(cleanup);
const result = (text: string, history = false): WorkspaceResult => ({
  title: "履歴",
  items: [
    {
      id: history ? "manual:one" : "draft-one",
      title: "記録",
      fields: history
        ? [
            ["Type", "AutonomousHistory"],
            ["Status", "SKIPPED"],
            ["Reason", text],
            ["DraftId", "draft-one"],
            ["Trigger", "MANUAL"],
            ["Kind", "NEW"],
            ["CreatedAt", "2026-11-28T03:00:00Z"],
          ]
        : [
            ["Type", "AutonomousDraft"],
            ["Status", "PUBLISHED"],
            ["Text", text],
            ["Account", "rei.example"],
            ["DID", "did:plc:example"],
            ["URI", "at://did:plc:example/app.bsky.feed.post/aaaaaaaaaaaaa"],
            ["CID", "bafyre-fixture"],
            ["UpdatedAt", "2026-11-28T03:00:01Z"],
          ],
    },
  ],
});
const fixture = (
  override?: (
    server: string,
    op: WorkspaceOperation,
  ) => Promise<WorkspaceResult>,
) => {
  const call = vi.fn(
    async (
      _: string,
      args: { serverId: string; operation: WorkspaceOperation },
    ) =>
      override
        ? override(args.serverId, args.operation)
        : result(
            args.operation.operation === "autonomousHistory"
              ? "no_suitable_topic"
              : "公開した本文",
            args.operation.operation === "autonomousHistory",
          ),
  );
  return {
    call,
    operations: () => call.mock.calls.map(([, args]) => args.operation),
  };
};

it("loads published and generation history with only bounded global reads and opens a linked draft explicitly", async () => {
  const f = fixture();
  const open = vi.fn();
  render(
    <AutonomousHistory call={f.call as Command} serverId="s" onDraft={open} />,
  );
  expect(await screen.findByText("公開した本文")).toBeTruthy();
  expect(await screen.findByText("no_suitable_topic")).toBeTruthy();
  expect(screen.getByText("did:plc:example")).toBeTruthy();
  expect(screen.getByText("bafyre-fixture")).toBeTruthy();
  expect(f.operations()).toEqual([
    { operation: "autonomousPublished", limit: 20 },
    { operation: "autonomousHistory", limit: 20 },
  ]);
  expect(open).not.toHaveBeenCalled();
  await userEvent.click(
    screen.getByRole("button", { name: "投稿案を開く draft-one" }),
  );
  expect(open).toHaveBeenCalledExactlyOnceWith("draft-one");
  expect(f.operations()).toHaveLength(2);
  expect(
    screen.queryByRole("button", { name: "この内容を公開する" }),
  ).toBeNull();
});

it("does not fetch history until an authenticated server is selected", () => {
  const f = fixture();
  render(
    <AutonomousHistory
      call={f.call as Command}
      serverId={null}
      onDraft={vi.fn()}
    />,
  );
  expect(screen.getByText(/サーバーを選択/)).toBeTruthy();
  expect(f.call).not.toHaveBeenCalled();
});

it("ignores late responses from a previously selected server", async () => {
  const finishes: ((value: WorkspaceResult) => void)[] = [];
  const f = fixture((server, op) =>
    server === "old"
      ? new Promise((resolve) => finishes.push(resolve))
      : Promise.resolve(
          result("NEW_SERVER_TEXT", op.operation === "autonomousHistory"),
        ),
  );
  const view = render(
    <AutonomousHistory
      call={f.call as Command}
      serverId="old"
      onDraft={vi.fn()}
    />,
  );
  await waitFor(() => expect(finishes).toHaveLength(2));
  view.rerender(
    <AutonomousHistory
      call={f.call as Command}
      serverId="new"
      onDraft={vi.fn()}
    />,
  );
  expect((await screen.findAllByText("NEW_SERVER_TEXT")).length).toBe(2);
  await act(async () =>
    finishes.forEach((finish) => finish(result("PRIVATE_OLD_SERVER"))),
  );
  expect(screen.queryByText("PRIVATE_OLD_SERVER")).toBeNull();
});

it("clears already displayed server data immediately while the next server is loading", async () => {
  const finishes: ((value: WorkspaceResult) => void)[] = [];
  const f = fixture((server, op) =>
    server === "new"
      ? new Promise((resolve) => finishes.push(resolve))
      : Promise.resolve(
          result("PRIVATE_OLD_SERVER", op.operation === "autonomousHistory"),
        ),
  );
  const view = render(
    <AutonomousHistory
      call={f.call as Command}
      serverId="old"
      onDraft={vi.fn()}
    />,
  );
  await screen.findAllByText("PRIVATE_OLD_SERVER");
  view.rerender(
    <AutonomousHistory
      call={f.call as Command}
      serverId="new"
      onDraft={vi.fn()}
    />,
  );
  expect(screen.queryByText("PRIVATE_OLD_SERVER")).toBeNull();
  await act(async () =>
    finishes.forEach((finish, index) =>
      finish(result("NEW_SERVER_TEXT", index === 1)),
    ),
  );
  expect((await screen.findAllByText("NEW_SERVER_TEXT")).length).toBe(2);
});

it("shows errors without automatic retry and refreshes using only the two read operations", async () => {
  let failed = true;
  const f = fixture(async (_server, op) => {
    if (failed) throw new Error("history_unavailable");
    return result("取得できました", op.operation === "autonomousHistory");
  });
  render(
    <AutonomousHistory
      call={f.call as Command}
      serverId="s"
      onDraft={vi.fn()}
    />,
  );
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(f.call).toHaveBeenCalledTimes(2);
  failed = false;
  await userEvent.click(screen.getByRole("button", { name: "履歴を更新" }));
  await screen.findAllByText("取得できました");
  expect(f.call).toHaveBeenCalledTimes(4);
  expect(
    f
      .operations()
      .every((op) =>
        ["autonomousPublished", "autonomousHistory"].includes(op.operation),
      ),
  ).toBe(true);
});

it("renders untrusted history text literally without executable markup or publication controls", async () => {
  const text = '<img src=x onerror="publish()"><script>publish()</script>';
  const f = fixture(async (_server, op) =>
    result(text, op.operation === "autonomousHistory"),
  );
  const view = render(
    <AutonomousHistory
      call={f.call as Command}
      serverId="s"
      onDraft={vi.fn()}
      disabled
    />,
  );
  await screen.findAllByText(text);
  expect(view.container.querySelector("img,script")).toBeNull();
  expect(screen.queryByRole("link")).toBeNull();
  expect(
    screen
      .getByRole("button", { name: "投稿案を開く draft-one" })
      .hasAttribute("disabled"),
  ).toBe(true);
});
