import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import type { WorkspaceResult } from "../../entities/workspace";
import { AutonomousNotices } from "./AutonomousNotices";

afterEach(cleanup);
const notice: WorkspaceResult = {
  title: "通知",
  items: [
    {
      id: "notice-one",
      title: "投稿案の確認待ち",
      fields: [
        ["Type", "AutonomousNotice"],
        ["DraftId", "draft-one"],
        ["Status", "OPEN"],
        ["CreatedAt", "2026-11-28T03:00:00Z"],
      ],
    },
  ],
};

it("opens a global draft reference and acknowledges without approval or publication", async () => {
  const call = vi.fn().mockResolvedValue(notice);
  const open = vi.fn();
  render(
    <AutonomousNotices
      call={call as Command}
      serverId="server-one"
      onOpenDraft={open}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "投稿案の詳細を開く" }),
  );
  expect(open).toHaveBeenCalledWith("server-one", "draft-one");
  expect(call).toHaveBeenCalledWith("workspace_execute", {
    serverId: "server-one",
    operation: { operation: "autonomousNotifications", limit: 100 },
  });
  call
    .mockResolvedValueOnce({ title: "既読", items: [] })
    .mockResolvedValueOnce({ title: "通知", items: [] });
  await userEvent.click(
    screen.getByRole("button", { name: "投稿案の通知を既読にする" }),
  );
  await waitFor(() =>
    expect(
      screen.queryByRole("button", { name: "投稿案の詳細を開く" }),
    ).toBeNull(),
  );
  expect(call).toHaveBeenCalledWith("workspace_execute", {
    serverId: "server-one",
    operation: { operation: "autonomousNoticeAck", id: "notice-one" },
  });
  expect(
    call.mock.calls.every(([, args]) =>
      ["autonomousNotifications", "autonomousNoticeAck"].includes(
        args.operation.operation,
      ),
    ),
  ).toBe(true);
});

it("masks old-server notices and ignores late responses after locking or switching", async () => {
  let finish: ((result: WorkspaceResult) => void) | undefined;
  const call = vi.fn().mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const open = vi.fn();
  const view = render(
    <AutonomousNotices
      call={call as Command}
      serverId="old"
      onOpenDraft={open}
    />,
  );
  await waitFor(() => expect(call).toHaveBeenCalledTimes(1));
  view.rerender(
    <AutonomousNotices
      call={call as Command}
      serverId={null}
      onOpenDraft={open}
    />,
  );
  await act(async () => finish?.(notice));
  expect(
    screen.queryByRole("button", { name: "投稿案の詳細を開く" }),
  ).toBeNull();
  expect(open).not.toHaveBeenCalled();
});

it("reports inbox failures and lets the user retry without touching drafts", async () => {
  const call = vi
    .fn()
    .mockRejectedValueOnce(new Error("unavailable"))
    .mockResolvedValue(notice);
  render(
    <AutonomousNotices
      call={call as Command}
      serverId="s"
      onOpenDraft={vi.fn()}
    />,
  );
  expect(await screen.findByRole("alert")).toBeTruthy();
  await userEvent.click(
    screen.getByRole("button", { name: "投稿案の通知を更新" }),
  );
  expect(
    await screen.findByRole("button", { name: "投稿案の詳細を開く" }),
  ).toBeTruthy();
});
